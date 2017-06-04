/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.cassandra.db.rows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.ClusteringIndexFilter;
import org.apache.cassandra.db.filter.ColumnFilter;
import org.apache.cassandra.io.sstable.IndexInfo;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.metadata.StatsMetadata;
import org.apache.cassandra.thrift.ThriftResultsMerger;
import org.apache.cassandra.utils.IteratorWithLowerBound;

/**
 * An unfiltered row iterator with a lower bound retrieved from either the global
 * sstable statistics or the row index lower bounds (if available in the cache).
 * Before initializing the sstable unfiltered row iterator, we return an empty row
 * with the clustering set to the lower bound. The empty row will be filtered out and
 * the result is that if we don't need to access this sstable, i.e. due to the LIMIT conditon,
 * then we will not. See CASSANDRA-8180 for examples of why this is useful.
 */
public class UnfilteredRowIteratorWithLowerBound extends LazilyInitializedUnfilteredRowIterator implements IteratorWithLowerBound<Unfiltered>
{
    private final SSTableReader sstable;
    private final ClusteringIndexFilter filter;
    private final ColumnFilter selectedColumns;
    private final boolean isForThrift;
    private final int nowInSec;
    private final boolean applyThriftTransformation;
    private ClusteringBound lowerBound;
    private boolean firstItemRetrieved;

    public UnfilteredRowIteratorWithLowerBound(DecoratedKey partitionKey,
                                               SSTableReader sstable,
                                               ClusteringIndexFilter filter,
                                               ColumnFilter selectedColumns,
                                               boolean isForThrift,
                                               int nowInSec,
                                               boolean applyThriftTransformation)
    {
        super(partitionKey);
        this.sstable = sstable;
        this.filter = filter;
        this.selectedColumns = selectedColumns;
        this.isForThrift = isForThrift;
        this.nowInSec = nowInSec;
        this.applyThriftTransformation = applyThriftTransformation;
        this.lowerBound = null;
        this.firstItemRetrieved = false;
    }

    public Unfiltered lowerBound()
    {
        if (lowerBound != null)
            return makeBound(lowerBound);

        // The partition index lower bound is more accurate than the sstable metadata lower bound but it is only
        // present if the iterator has already been initialized, which we only do when there are tombstones since in
        // this case we cannot use the sstable metadata clustering values
        ClusteringBound ret = getPartitionIndexLowerBound();
        return ret != null ? makeBound(ret) : makeBound(getMetadataLowerBound());
    }

    private Unfiltered makeBound(ClusteringBound bound)
    {
        if (bound == null)
            return null;

        if (lowerBound != bound)
            lowerBound = bound;

        return new RangeTombstoneBoundMarker(lowerBound, DeletionTime.LIVE);
    }

    @Override
    protected UnfilteredRowIterator initializeIterator()
    {
        sstable.incrementReadCount();

        @SuppressWarnings("resource") // 'iter' is added to iterators which is closed on exception, or through the closing of the final merged iterator
        UnfilteredRowIterator iter = sstable.iterator(partitionKey(), filter.getSlices(metadata()), selectedColumns, filter.isReversed(), isForThrift);
        return isForThrift && applyThriftTransformation
               ? ThriftResultsMerger.maybeWrap(iter, nowInSec)
               : iter;
    }

    @Override
    protected Unfiltered computeNext()
    {
        Unfiltered ret = super.computeNext();
        if (firstItemRetrieved)
            return ret;

        // Check that the lower bound is not bigger than the first item retrieved
        firstItemRetrieved = true;
        if (lowerBound != null && ret != null)
            assert comparator().compare(lowerBound, ret.clustering()) <= 0
                : String.format("Lower bound [%s ]is bigger than first returned value [%s] for sstable %s",
                                lowerBound.toString(sstable.metadata),
                                ret.toString(sstable.metadata),
                                sstable.getFilename());

        return ret;
    }

    private Comparator<Clusterable> comparator()
    {
        return filter.isReversed() ? sstable.metadata.comparator.reversed() : sstable.metadata.comparator;
    }

    @Override
    public CFMetaData metadata()
    {
        return sstable.metadata;
    }

    @Override
    public boolean isReverseOrder()
    {
        return filter.isReversed();
    }

    @Override
    public PartitionColumns columns()
    {
        return selectedColumns.fetchedColumns();
    }

    @Override
    public EncodingStats stats()
    {
        return sstable.stats();
    }

    @Override
    public DeletionTime partitionLevelDeletion()
    {
        if (!sstable.hasTombstones())
            return DeletionTime.LIVE;

        return super.partitionLevelDeletion();
    }

    @Override
    public Row staticRow()
    {
        if (columns().statics.isEmpty())
            return Rows.EMPTY_STATIC_ROW;

        return super.staticRow();
    }

    /**
     * @return the lower bound stored on the index entry for this partition, if available.
     */
    private ClusteringBound getPartitionIndexLowerBound()
    {
        // NOTE: CASSANDRA-11206 removed the lookup against the key-cache as the IndexInfo objects are no longer
        // in memory for not heap backed IndexInfo objects (so, these are on disk).
        // CASSANDRA-11369 is there to fix this afterwards.

        // Creating the iterator ensures that rowIndexEntry is loaded if available (partitions bigger than
        // DatabaseDescriptor.column_index_size_in_kb)
        if (!canUseMetadataLowerBound())
            maybeInit();

        RowIndexEntry rowIndexEntry = sstable.getCachedPosition(partitionKey(), false);
        if (rowIndexEntry == null || !rowIndexEntry.indexOnHeap())
            return null;

        try (RowIndexEntry.IndexInfoRetriever onHeapRetriever = rowIndexEntry.openWithIndex(null))
        {
            IndexInfo column = onHeapRetriever.columnsIndex(filter.isReversed() ? rowIndexEntry.columnsIndexCount() - 1 : 0);
            ClusteringPrefix lowerBoundPrefix = filter.isReversed() ? column.lastName : column.firstName;
            assert lowerBoundPrefix.getRawValues().length <= sstable.metadata.comparator.size() :
            String.format("Unexpected number of clustering values %d, expected %d or fewer for %s",
                          lowerBoundPrefix.getRawValues().length,
                          sstable.metadata.comparator.size(),
                          sstable.getFilename());
            return ClusteringBound.inclusiveOpen(filter.isReversed(), lowerBoundPrefix.getRawValues());
        }
        catch (IOException e)
        {
            throw new RuntimeException("should never occur", e);
        }
    }

    /**
     * @return true if we can use the clustering values in the stats of the sstable:
     * - we need the latest stats file format (or else the clustering values create clusterings with the wrong size)
     * - we cannot create tombstone bounds from these values only and so we rule out sstables with tombstones
     */
    private boolean canUseMetadataLowerBound()
    {
        return !sstable.hasTombstones() && sstable.descriptor.version.hasNewStatsFile();
    }

    /**
     * @return a global lower bound made from the clustering values stored in the sstable metadata, note that
     * this currently does not correctly compare tombstone bounds, especially ranges.
     */
    private ClusteringBound getMetadataLowerBound()
    {
        if (!canUseMetadataLowerBound())
            return null;

        final StatsMetadata m = sstable.getSSTableMetadata();
        List<ByteBuffer> vals = filter.isReversed() ? m.maxClusteringValues : m.minClusteringValues;
        assert vals.size() <= sstable.metadata.comparator.size() :
        String.format("Unexpected number of clustering values %d, expected %d or fewer for %s",
                      vals.size(),
                      sstable.metadata.comparator.size(),
                      sstable.getFilename());
        return  ClusteringBound.inclusiveOpen(filter.isReversed(), vals.toArray(new ByteBuffer[vals.size()]));
    }
}
