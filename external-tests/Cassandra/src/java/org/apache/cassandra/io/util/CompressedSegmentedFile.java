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
package org.apache.cassandra.io.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;

import org.apache.cassandra.cache.ChunkCache;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.Config.DiskAccessMode;
import org.apache.cassandra.io.compress.*;
import org.apache.cassandra.io.sstable.CorruptSSTableException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.concurrent.Ref;

public class CompressedSegmentedFile extends SegmentedFile implements ICompressedFile
{
    public final CompressionMetadata metadata;

    public CompressedSegmentedFile(ChannelProxy channel, CompressionMetadata metadata, Config.DiskAccessMode mode)
    {
        this(channel,
             metadata,
             mode == DiskAccessMode.mmap
             ? MmappedRegions.map(channel, metadata)
             : null);
    }

    public CompressedSegmentedFile(ChannelProxy channel, CompressionMetadata metadata, MmappedRegions regions)
    {
        this(channel, metadata, regions, createRebufferer(channel, metadata, regions));
    }

    private static RebuffererFactory createRebufferer(ChannelProxy channel, CompressionMetadata metadata, MmappedRegions regions)
    {
        return ChunkCache.maybeWrap(chunkReader(channel, metadata, regions));
    }

    public static ChunkReader chunkReader(ChannelProxy channel, CompressionMetadata metadata, MmappedRegions regions)
    {
        return regions != null
               ? new Mmap(channel, metadata, regions)
               : new Standard(channel, metadata);
    }

    public CompressedSegmentedFile(ChannelProxy channel, CompressionMetadata metadata, MmappedRegions regions, RebuffererFactory rebufferer)
    {
        super(new Cleanup(channel, metadata, regions, rebufferer), channel, rebufferer, metadata.compressedFileLength);
        this.metadata = metadata;
    }

    private CompressedSegmentedFile(CompressedSegmentedFile copy)
    {
        super(copy);
        this.metadata = copy.metadata;
    }

    public ChannelProxy channel()
    {
        return channel;
    }

    private static final class Cleanup extends SegmentedFile.Cleanup
    {
        final CompressionMetadata metadata;

        protected Cleanup(ChannelProxy channel, CompressionMetadata metadata, MmappedRegions regions, ReaderFileProxy rebufferer)
        {
            super(channel, rebufferer);
            this.metadata = metadata;
        }
        public void tidy()
        {
            if (ChunkCache.instance != null)
            {
                ChunkCache.instance.invalidateFile(name());
            }
            metadata.close();

            super.tidy();
        }
    }

    public CompressedSegmentedFile sharedCopy()
    {
        return new CompressedSegmentedFile(this);
    }

    public void addTo(Ref.IdentityCollection identities)
    {
        super.addTo(identities);
        metadata.addTo(identities);
    }

    public static class Builder extends SegmentedFile.Builder
    {
        final CompressedSequentialWriter writer;
        final Config.DiskAccessMode mode;

        public Builder(CompressedSequentialWriter writer)
        {
            this.writer = writer;
            this.mode = DatabaseDescriptor.getDiskAccessMode();
        }

        protected CompressionMetadata metadata(String path, long overrideLength)
        {
            if (writer == null)
                return CompressionMetadata.create(path);

            return writer.open(overrideLength);
        }

        public SegmentedFile complete(ChannelProxy channel, int bufferSize, long overrideLength)
        {
            return new CompressedSegmentedFile(channel, metadata(channel.filePath(), overrideLength), mode);
        }
    }

    public void dropPageCache(long before)
    {
        if (before >= metadata.dataLength)
            super.dropPageCache(0);
        super.dropPageCache(metadata.chunkFor(before).offset);
    }

    public CompressionMetadata getMetadata()
    {
        return metadata;
    }

    public long dataLength()
    {
        return metadata.dataLength;
    }

    @VisibleForTesting
    public abstract static class CompressedChunkReader extends AbstractReaderFileProxy implements ChunkReader
    {
        final CompressionMetadata metadata;

        public CompressedChunkReader(ChannelProxy channel, CompressionMetadata metadata)
        {
            super(channel, metadata.dataLength);
            this.metadata = metadata;
            assert Integer.bitCount(metadata.chunkLength()) == 1; //must be a power of two
        }

        @VisibleForTesting
        public double getCrcCheckChance()
        {
            return metadata.parameters.getCrcCheckChance();
        }

        @Override
        public String toString()
        {
            return String.format("CompressedChunkReader.%s(%s - %s, chunk length %d, data length %d)",
                                 getClass().getSimpleName(),
                                 channel.filePath(),
                                 metadata.compressor().getClass().getSimpleName(),
                                 metadata.chunkLength(),
                                 metadata.dataLength);
        }

        @Override
        public int chunkSize()
        {
            return metadata.chunkLength();
        }

        @Override
        public boolean alignmentRequired()
        {
            return true;
        }

        @Override
        public BufferType preferredBufferType()
        {
            return metadata.compressor().preferredBufferType();
        }

        @Override
        public Rebufferer instantiateRebufferer()
        {
            return BufferManagingRebufferer.on(this);
        }
    }

    static class Standard extends CompressedChunkReader
    {
        // we read the raw compressed bytes into this buffer, then uncompressed them into the provided one.
        private final ThreadLocal<ByteBuffer> compressedHolder;

        public Standard(ChannelProxy channel, CompressionMetadata metadata)
        {
            super(channel, metadata);
            compressedHolder = ThreadLocal.withInitial(this::allocateBuffer);
        }

        public ByteBuffer allocateBuffer()
        {
            return allocateBuffer(metadata.compressor().initialCompressedBufferLength(metadata.chunkLength()));
        }

        public ByteBuffer allocateBuffer(int size)
        {
            return metadata.compressor().preferredBufferType().allocate(size);
        }

        @Override
        public void readChunk(long position, ByteBuffer uncompressed)
        {
            try
            {
                // accesses must always be aligned
                assert (position & -uncompressed.capacity()) == position;
                assert position <= fileLength;

                CompressionMetadata.Chunk chunk = metadata.chunkFor(position);
                ByteBuffer compressed = compressedHolder.get();

                if (compressed.capacity() < chunk.length)
                {
                    compressed = allocateBuffer(chunk.length);
                    compressedHolder.set(compressed);
                }
                else
                {
                    compressed.clear();
                }

                compressed.limit(chunk.length);
                if (channel.read(compressed, chunk.offset) != chunk.length)
                    throw new CorruptBlockException(channel.filePath(), chunk);

                compressed.flip();
                uncompressed.clear();

                try
                {
                    metadata.compressor().uncompress(compressed, uncompressed);
                }
                catch (IOException e)
                {
                    throw new CorruptBlockException(channel.filePath(), chunk);
                }
                finally
                {
                    uncompressed.flip();
                }

                if (getCrcCheckChance() > ThreadLocalRandom.current().nextDouble())
                {
                    compressed.rewind();
                    int checksum = (int) metadata.checksumType.of(compressed);

                    compressed.clear().limit(Integer.BYTES);
                    if (channel.read(compressed, chunk.offset + chunk.length) != Integer.BYTES
                        || compressed.getInt(0) != checksum)
                        throw new CorruptBlockException(channel.filePath(), chunk);
                }
            }
            catch (CorruptBlockException e)
            {
                throw new CorruptSSTableException(e, channel.filePath());
            }
        }
    }

    static class Mmap extends CompressedChunkReader
    {
        protected final MmappedRegions regions;

        public Mmap(ChannelProxy channel, CompressionMetadata metadata, MmappedRegions regions)
        {
            super(channel, metadata);
            this.regions = regions;
        }

        @Override
        public void readChunk(long position, ByteBuffer uncompressed)
        {
            try
            {
                // accesses must always be aligned
                assert (position & -uncompressed.capacity()) == position;
                assert position <= fileLength;

                CompressionMetadata.Chunk chunk = metadata.chunkFor(position);

                MmappedRegions.Region region = regions.floor(chunk.offset);
                long segmentOffset = region.offset();
                int chunkOffset = Ints.checkedCast(chunk.offset - segmentOffset);
                ByteBuffer compressedChunk = region.buffer();

                compressedChunk.position(chunkOffset).limit(chunkOffset + chunk.length);

                uncompressed.clear();

                try
                {
                    metadata.compressor().uncompress(compressedChunk, uncompressed);
                }
                catch (IOException e)
                {
                    throw new CorruptBlockException(channel.filePath(), chunk);
                }
                finally
                {
                    uncompressed.flip();
                }

                if (getCrcCheckChance() > ThreadLocalRandom.current().nextDouble())
                {
                    compressedChunk.position(chunkOffset).limit(chunkOffset + chunk.length);

                    int checksum = (int) metadata.checksumType.of(compressedChunk);

                    compressedChunk.limit(compressedChunk.capacity());
                    if (compressedChunk.getInt() != checksum)
                        throw new CorruptBlockException(channel.filePath(), chunk);
                }
            }
            catch (CorruptBlockException e)
            {
                throw new CorruptSSTableException(e, channel.filePath());
            }

        }

        public void close()
        {
            regions.closeQuietly();
            super.close();
        }
    }
}
