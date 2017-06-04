/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.db.commitlog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.commitlog.CommitLogSegment.CDCState;
import org.apache.cassandra.exceptions.WriteTimeoutException;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.DirectorySizeCalculator;

public class CommitLogSegmentManagerCDC extends AbstractCommitLogSegmentManager
{
    static final Logger logger = LoggerFactory.getLogger(CommitLogSegmentManagerCDC.class);
    private final CDCSizeTracker cdcSizeTracker;

    public CommitLogSegmentManagerCDC(final CommitLog commitLog, String storageDirectory)
    {
        super(commitLog, storageDirectory);
        cdcSizeTracker = new CDCSizeTracker(this, new File(DatabaseDescriptor.getCDCLogLocation()));
    }

    @Override
    void start()
    {
        super.start();
        cdcSizeTracker.start();
    }

    public void discard(CommitLogSegment segment, boolean delete)
    {
        segment.close();
        addSize(-segment.onDiskSize());

        cdcSizeTracker.processDiscardedSegment(segment);

        if (segment.getCDCState() == CDCState.CONTAINS)
            FileUtils.renameWithConfirm(segment.logFile.getAbsolutePath(), DatabaseDescriptor.getCDCLogLocation() + File.separator + segment.logFile.getName());
        else
        {
            if (delete)
                FileUtils.deleteWithConfirm(segment.logFile);
        }
    }

    /**
     * Initiates the shutdown process for the management thread. Also stops the cdc on-disk size calculator executor.
     */
    public void shutdown()
    {
        run = false;
        cdcSizeTracker.shutdown();
        wakeManager();
    }

    /**
     * Reserve space in the current segment for the provided mutation or, if there isn't space available,
     * create a new segment. For CDC mutations, allocation is expected to throw WTE if the segment disallows CDC mutations.
     *
     * @param mutation Mutation to allocate in segment manager
     * @param size total size (overhead + serialized) of mutation
     * @return the created Allocation object
     * @throws WriteTimeoutException If segment disallows CDC mutations, we throw WTE
     */
    @Override
    public CommitLogSegment.Allocation allocate(Mutation mutation, int size) throws WriteTimeoutException
    {
        CommitLogSegment segment = allocatingFrom();
        CommitLogSegment.Allocation alloc;

        throwIfForbidden(mutation, segment);
        while ( null == (alloc = segment.allocate(mutation, size)) )
        {
            // Failed to allocate, so move to a new segment with enough room if possible.
            advanceAllocatingFrom(segment);
            segment = allocatingFrom;

            throwIfForbidden(mutation, segment);
        }

        if (mutation.trackedByCDC())
            segment.setCDCState(CDCState.CONTAINS);

        return alloc;
    }

    private void throwIfForbidden(Mutation mutation, CommitLogSegment segment) throws WriteTimeoutException
    {
        if (mutation.trackedByCDC() && segment.getCDCState() == CDCState.FORBIDDEN)
        {
            cdcSizeTracker.submitOverflowSizeRecalculation();
            throw new WriteTimeoutException(WriteType.CDC, ConsistencyLevel.LOCAL_ONE, 0, 1);
        }
    }

    /**
     * Move files to cdc_raw after replay, since recovery will flush to SSTable and these mutations won't be available
     * in the CL subsystem otherwise.
     */
    void handleReplayedSegment(final File file)
    {
        logger.trace("Moving (Unopened) segment {} to cdc_raw directory after replay", file);
        FileUtils.renameWithConfirm(file.getAbsolutePath(), DatabaseDescriptor.getCDCLogLocation() + File.separator + file.getName());
        cdcSizeTracker.addFlushedSize(file.length());
    }

    /**
     * On segment creation, flag whether the segment should accept CDC mutations or not based on the total currently
     * allocated unflushed CDC segments and the contents of cdc_raw
     */
    public CommitLogSegment createSegment()
    {
        CommitLogSegment segment = CommitLogSegment.createSegment(commitLog, this, () -> wakeManager());
        cdcSizeTracker.processNewSegment(segment);
        return segment;
    }

    /**
     * Tracks total disk usage of CDC subsystem, defined by the summation of all unflushed CommitLogSegments with CDC
     * data in them and all segments archived into cdc_raw.
     *
     * Allows atomic increment/decrement of unflushed size, however only allows increment on flushed and requires a full
     * directory walk to determine any potential deletions by CDC consumer.
     *
     * TODO: linux performs approximately 25% better with the following one-liner instead of this walker:
     *      Arrays.stream(path.listFiles()).mapToLong(File::length).sum();
     * However this solution is 375% slower on Windows. Revisit this and split logic to per-OS
     */
    private class CDCSizeTracker extends DirectorySizeCalculator
    {
        private final RateLimiter rateLimiter = RateLimiter.create(1000 / DatabaseDescriptor.getCDCDiskCheckInterval());
        private ExecutorService cdcSizeCalculationExecutor;
        private CommitLogSegmentManagerCDC segmentManager;
        private AtomicLong unflushedCDCSize = new AtomicLong(0);

        CDCSizeTracker(CommitLogSegmentManagerCDC segmentManager, File path)
        {
            super(path);
            this.segmentManager = segmentManager;
        }

        /**
         * Needed for stop/restart during unit tests
         */
        public void start()
        {
            cdcSizeCalculationExecutor = new ThreadPoolExecutor(1, 1, 1000, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
        }

        /**
         * Synchronous size recalculation on each segment creation/deletion call could lead to very long delays in new
         * segment allocation, thus long delays in thread signaling to wake waiting allocation / writer threads.
         *
         * This can be reached either from the segment management thread in ABstractCommitLogSegmentManager or from the
         * size recalculation executor, so we synchronize on this object to reduce the race overlap window available for
         * size to get off.
         *
         * Reference DirectorySizerBench for more information about performance of the directory size recalc.
         */
        void processNewSegment(CommitLogSegment segment)
        {
            // See synchronization in CommitLogSegment.setCDCState
            synchronized(segment.cdcStateLock)
            {
                segment.setCDCState(defaultSegmentSize() + totalCDCSizeOnDisk() > allowableCDCBytes()
                                    ? CDCState.FORBIDDEN
                                    : CDCState.PERMITTED);
                if (segment.getCDCState() == CDCState.PERMITTED)
                    unflushedCDCSize.addAndGet(defaultSegmentSize());
            }

            // Take this opportunity to kick off a recalc to pick up any consumer file deletion.
            submitOverflowSizeRecalculation();
        }

        void processDiscardedSegment(CommitLogSegment segment)
        {
            // See synchronization in CommitLogSegment.setCDCState
            synchronized(segment.cdcStateLock)
            {
                // Add to flushed size before decrementing unflushed so we don't have a window of false generosity
                if (segment.getCDCState() == CDCState.CONTAINS)
                    size.addAndGet(segment.onDiskSize());
                if (segment.getCDCState() != CDCState.FORBIDDEN)
                    unflushedCDCSize.addAndGet(-defaultSegmentSize());
            }

            // Take this opportunity to kick off a recalc to pick up any consumer file deletion.
            submitOverflowSizeRecalculation();
        }

        private long allowableCDCBytes()
        {
            return (long)DatabaseDescriptor.getCDCSpaceInMB() * 1024 * 1024;
        }

        public void submitOverflowSizeRecalculation()
        {
            try
            {
                cdcSizeCalculationExecutor.submit(() -> recalculateOverflowSize());
            }
            catch (RejectedExecutionException e)
            {
                // Do nothing. Means we have one in flight so this req. should be satisfied when it completes.
            }
        }

        private void recalculateOverflowSize()
        {
            rateLimiter.acquire();
            calculateSize();
            CommitLogSegment allocatingFrom = segmentManager.allocatingFrom;
            if (allocatingFrom.getCDCState() == CDCState.FORBIDDEN)
                processNewSegment(allocatingFrom);
        }

        private int defaultSegmentSize()
        {
            return DatabaseDescriptor.getCommitLogSegmentSize();
        }

        private void calculateSize()
        {
            try
            {
                // Since we don't synchronize around either rebuilding our file list or walking the tree and adding to
                // size, it's possible we could have changes take place underneath us and end up with a slightly incorrect
                // view of our flushed size by the time this walking completes. Given that there's a linear growth in
                // runtime on both rebuildFileList and walkFileTree (about 50% for each one on runtime), and that the
                // window for this race should be very small, this is an acceptable trade-off since it will be resolved
                // on the next segment creation / deletion with a subsequent call to submitOverflowSizeRecalculation.
                rebuildFileList();
                Files.walkFileTree(path.toPath(), this);
            }
            catch (IOException ie)
            {
                CommitLog.instance.handleCommitError("Failed CDC Size Calculation", ie);
            }
        }

        private long addFlushedSize(long toAdd)
        {
            return size.addAndGet(toAdd);
        }

        private long totalCDCSizeOnDisk()
        {
            return unflushedCDCSize.get() + size.get();
        }

        public void shutdown()
        {
            cdcSizeCalculationExecutor.shutdown();
        }
    }

    /**
     * Only use for testing / validation that size tracker is working. Not for production use.
     */
    @VisibleForTesting
    public long updateCDCTotalSize()
    {
        cdcSizeTracker.submitOverflowSizeRecalculation();

        // Give the update time to run
        try
        {
            Thread.sleep(DatabaseDescriptor.getCDCDiskCheckInterval() + 10);
        }
        catch (InterruptedException e) {}

        return cdcSizeTracker.totalCDCSizeOnDisk();
    }
}
