/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.world.region.linear;

import abomination.IRegionFile;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.dreeam.leaf.config.modules.misc.RegionFormatConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;
import java.util.zip.CRC32C;

/**
 * Production-ready Linear V3 region storage backend implementing zero-leak Zstd JNI memory discipline,
 * zero-copy I/O via Netty pooled off-heap buffers, optimistic StampedLock concurrency,
 * and zero-recompression vacuum compaction.
 */
public class LinearV3RegionFile implements IRegionFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinearV3RegionFile.class);

    public static final long SUPERBLOCK_MAGIC = 0x4C494E4541525633L; // "LINEARV3"
    public static final short FORMAT_VERSION = 3;

    public static final int HEADER_SIZE = 64;
    public static final int INDEX_TABLE_SIZE = 1024 * 16;       // 16,384 bytes
    public static final int TIMESTAMP_TABLE_SIZE = 1024 * 4;     // 4,096 bytes
    public static final int METADATA_TOTAL_SIZE = HEADER_SIZE + INDEX_TABLE_SIZE + TIMESTAMP_TABLE_SIZE; // 20,544 bytes

    public static final int MAX_UNCOMPRESSED_CHUNK_SIZE = 16 * 1024 * 1024; // 16 MB anti-OOM cap
    private static final int INITIAL_WRITE_BUFFER_SIZE = 65536; // 64 KB

    private final Path filePath;
    private final Path folder;
    private final boolean sync;
    private final int compressionLevel;
    private final RegionStorageInfo storageInfo;

    private final StampedLock lock = new StampedLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // In-memory index & timestamp tables (only ~20 KB of primitive arrays per region)
    private final int[] chunkOffsets = new int[1024];
    private final int[] chunkCompressedSizes = new int[1024];
    private final int[] chunkUncompressedSizes = new int[1024];
    private final int[] chunkCRC32Cs = new int[1024];
    private final int[] chunkTimestamps = new int[1024];

    // Single pre-allocated direct scratch buffer strictly guarded by lock.writeLock()
    private final ByteBuffer writeScratchBuf = ByteBuffer.allocateDirect(64);

    private int activeChunkCount = 0;
    private long deadSpaceBytes = 0;
    private long fileEndOffset = METADATA_TOTAL_SIZE;

    private volatile FileChannel fileChannel;

    public LinearV3RegionFile(Path filePath, int compressionLevel) throws IOException {
        this(null, filePath, filePath.getParent(), false, compressionLevel);
    }

    public LinearV3RegionFile(
        @Nullable RegionStorageInfo storageInfo,
        Path filePath,
        Path folder,
        boolean sync,
        int compressionLevel
    ) throws IOException {
        this.storageInfo = storageInfo;
        this.filePath = filePath;
        this.folder = folder;
        this.sync = sync;
        this.compressionLevel = Math.max(1, Math.min(compressionLevel, 22));

        if (this.folder != null) {
            Files.createDirectories(this.folder);
        }

        initFile();
    }

    private void initFile() throws IOException {
        boolean exists = Files.exists(this.filePath);
        long length = exists ? Files.size(this.filePath) : 0;

        if (!exists || length == 0) {
            this.fileChannel = FileChannel.open(this.filePath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                this.fileEndOffset = METADATA_TOTAL_SIZE;
                writeInitialMetadata();
            } catch (Throwable t) {
                try {
                    this.fileChannel.close();
                } catch (Throwable ignored) {}
                throw t;
            }
        } else {
            this.fileChannel = FileChannel.open(this.filePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                readExistingMetadata(length);
            } catch (Throwable t) {
                try {
                    this.fileChannel.close();
                } catch (Throwable ignored) {}
                throw t;
            }
        }
    }

    private void writeInitialMetadata() throws IOException {
        ByteBuffer metaBuf = ByteBuffer.allocate(METADATA_TOTAL_SIZE);
        writeSuperblockToBuffer(metaBuf, 0, 0);
        // Index and Timestamp tables are zero-initialized
        metaBuf.position(0);
        metaBuf.limit(METADATA_TOTAL_SIZE);
        writeFully(this.fileChannel, metaBuf, 0);
        if (this.sync) {
            this.fileChannel.force(true);
        }
    }

    private void writeSuperblockToBuffer(ByteBuffer buf, int activeChunks, long deadSpace) {
        int originalLimit = buf.limit();
        buf.position(0);
        buf.putLong(SUPERBLOCK_MAGIC);
        buf.putShort(FORMAT_VERSION);
        short flags = (short) (LinearV3ZstdContextPool.getDictionaryHash() != 0 ? 0x01 : 0x00);
        buf.putShort(flags);
        buf.putInt(LinearV3ZstdContextPool.getDictionaryHash());
        buf.putInt(this.compressionLevel);
        buf.putInt(activeChunks);
        buf.putLong(0L); // walOffset
        buf.putInt(0);   // walEntryCount
        buf.putLong(deadSpace);
        buf.put(new byte[16]); // reserved zero-padding

        CRC32C crc = new CRC32C();
        buf.position(0);
        buf.limit(60);
        crc.update(buf);
        buf.limit(originalLimit);
        buf.position(60);
        buf.putInt((int) crc.getValue());
    }

    private void readExistingMetadata(long length) throws IOException {
        if (length < METADATA_TOTAL_SIZE) {
            throw new IOException("File too small to be valid Linear V3 region: " + this.filePath + " (" + length + " bytes)");
        }

        ByteBuffer metaBuf = ByteBuffer.allocate(METADATA_TOTAL_SIZE);
        readFully(this.fileChannel, metaBuf, 0, METADATA_TOTAL_SIZE);
        metaBuf.flip();

        long magic = metaBuf.getLong();
        if (magic != SUPERBLOCK_MAGIC) {
            throw new IOException("Invalid Linear V3 magic signature: 0x" + Long.toHexString(magic) + " in " + this.filePath);
        }

        short version = metaBuf.getShort();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported Linear V3 version: " + version + " in " + this.filePath);
        }

        short flags = metaBuf.getShort();
        int dictHash = metaBuf.getInt();
        int fileCompLevel = metaBuf.getInt();
        int headerActiveChunks = metaBuf.getInt();
        long walOffset = metaBuf.getLong();
        int walEntryCount = metaBuf.getInt();
        long headerDeadSpace = metaBuf.getLong();
        metaBuf.position(metaBuf.position() + 16); // skip reserved

        if ((flags & 0x01) != 0 || dictHash != 0) {
            int currentDictHash = LinearV3ZstdContextPool.getDictionaryHash();
            if (dictHash != currentDictHash) {
                throw new IOException("Linear V3 region requires Zstd dictionary 0x" + Integer.toHexString(dictHash) +
                    ", but current loaded dictionary is 0x" + Integer.toHexString(currentDictHash) + " in " + this.filePath);
            }
        }

        CRC32C crc = new CRC32C();
        ByteBuffer headerSlice = metaBuf.duplicate();
        headerSlice.position(0);
        headerSlice.limit(60);
        crc.update(headerSlice);
        int expectedHeaderCrc = (int) crc.getValue();
        metaBuf.position(60);
        int actualHeaderCrc = metaBuf.getInt();
        if (expectedHeaderCrc != actualHeaderCrc) {
            throw new IOException("Linear V3 superblock header CRC32C corruption in " + this.filePath +
                " (expected=0x" + Integer.toHexString(expectedHeaderCrc) + ", actual=0x" + Integer.toHexString(actualHeaderCrc) + ")");
        }

        metaBuf.position(HEADER_SIZE);
        int scannedActiveChunks = 0;
        long maxPayloadEnd = METADATA_TOTAL_SIZE;

        for (int i = 0; i < 1024; i++) {
            int offset = metaBuf.getInt();
            int compSize = metaBuf.getInt();
            int uncompSize = metaBuf.getInt();
            int chunkCrc = metaBuf.getInt();

            chunkOffsets[i] = offset;
            chunkCompressedSizes[i] = compSize;
            chunkUncompressedSizes[i] = uncompSize;
            chunkCRC32Cs[i] = chunkCrc;

            if (offset > 0 && compSize > 0) {
                scannedActiveChunks++;
                long end = Integer.toUnsignedLong(offset) + compSize;
                if (end > maxPayloadEnd) {
                    maxPayloadEnd = end;
                }
            }
        }

        for (int i = 0; i < 1024; i++) {
            chunkTimestamps[i] = metaBuf.getInt();
        }

        this.activeChunkCount = scannedActiveChunks;
        this.deadSpaceBytes = Math.max(0L, headerDeadSpace);
        this.fileEndOffset = Math.max(length, maxPayloadEnd);
    }

    public static int getChunkIndex(int x, int z) {
        return (x & 31) | ((z & 31) << 5);
    }

    @Override
    public Path getPath() {
        return this.filePath;
    }

    @Override
    public boolean hasChunk(ChunkPos pos) {
        int idx = getChunkIndex(pos.x, pos.z);
        long stamp = this.lock.tryOptimisticRead();
        int size = this.chunkCompressedSizes[idx];
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                size = this.chunkCompressedSizes[idx];
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return size > 0;
    }

    @Override
    public boolean doesChunkExist(ChunkPos pos) {
        return hasChunk(pos);
    }

    @Nullable
    @Override
    public DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException {
        int idx = getChunkIndex(pos.x, pos.z);
        long offset;
        int compSize;
        int uncompSize;
        int expectedCrc;

        ByteBuf compressedBuf = null;
        ByteBuf uncompressedBuf = null;

        // Hold readLock during metadata resolution AND file read to prevent race with performCompaction()
        long stamp = this.lock.readLock();
        try {
            if (this.closed.get()) {
                throw new ClosedChannelException();
            }

            offset = Integer.toUnsignedLong(this.chunkOffsets[idx]);
            compSize = this.chunkCompressedSizes[idx];
            uncompSize = this.chunkUncompressedSizes[idx];
            expectedCrc = this.chunkCRC32Cs[idx];

            if (offset == 0 || compSize <= 0) {
                return null;
            }

            if (uncompSize <= 0 || uncompSize > MAX_UNCOMPRESSED_CHUNK_SIZE) {
                throw new IOException("Corrupted chunk uncompressed size in " + this.filePath + " at " + pos + ": " + uncompSize);
            }

            compressedBuf = PooledByteBufAllocator.DEFAULT.directBuffer(compSize);
            ByteBuffer nioCompressed = compressedBuf.nioBuffer(0, compSize);
            readFully(this.fileChannel, nioCompressed, offset, compSize);
        } catch (Throwable t) {
            if (compressedBuf != null) {
                compressedBuf.release();
            }
            throw t;
        } finally {
            this.lock.unlockRead(stamp);
        }

        // Positional zero-copy CRC32C verification and decompress executed completely outside any lock
        byte[] resultBytes = new byte[uncompSize];
        uncompressedBuf = PooledByteBufAllocator.DEFAULT.directBuffer(uncompSize);

        try {
            ByteBuffer nioCompressed = compressedBuf.nioBuffer(0, compSize);

            // Hardware-accelerated CRC32C verification on compressed frame
            nioCompressed.position(0);
            nioCompressed.limit(compSize);
            CRC32C crc = new CRC32C();
            crc.update(nioCompressed);
            int actualCrc = (int) crc.getValue();
            if (actualCrc != expectedCrc) {
                throw new IOException("Linear V3 chunk CRC32C checksum mismatch at " + pos + " in " + this.filePath +
                    " (expected=0x" + Integer.toHexString(expectedCrc) + ", actual=0x" + Integer.toHexString(actualCrc) + ")");
            }

            // Zero-leak JNI decompress
            ByteBuffer nioUncompressed = uncompressedBuf.nioBuffer(0, uncompSize);
            nioCompressed.position(0);
            nioCompressed.limit(compSize);

            int decompressedBytes = LinearV3ZstdContextPool.decompress(
                nioUncompressed, 0, uncompSize,
                nioCompressed, 0, compSize
            );

            if (decompressedBytes != uncompSize) {
                throw new IOException("Decompressed size mismatch at " + pos + ": expected " + uncompSize + ", got " + decompressedBytes);
            }

            uncompressedBuf.getBytes(0, resultBytes);
            return new DataInputStream(new ByteArrayInputStream(resultBytes));
        } finally {
            if (compressedBuf != null) {
                compressedBuf.release();
            }
            if (uncompressedBuf != null) {
                uncompressedBuf.release();
            }
        }
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        return new DataOutputStream(new ChunkBuffer(pos));
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) {
        final DataOutputStream out = this.getChunkDataOutputStream(pos);
        return new MoonriseRegionFileIO.RegionDataController.WriteData(
            data,
            MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            out,
            regionFile -> out.close()
        );
    }

    @Override
    public void write(ChunkPos pos, ByteBuffer buf) throws IOException {
        int uncompressedSize = buf.remaining();
        if (uncompressedSize <= 0 || uncompressedSize > MAX_UNCOMPRESSED_CHUNK_SIZE) {
            throw new IOException("Invalid chunk uncompressed size to write at " + pos + ": " + uncompressedSize);
        }

        ByteBuf rawBuf = PooledByteBufAllocator.DEFAULT.directBuffer(uncompressedSize);
        try {
            rawBuf.writeBytes(buf);
            compressAndCommit(pos, rawBuf);
        } finally {
            rawBuf.release();
        }
    }

    private void compressAndCommit(ChunkPos pos, ByteBuf rawBuf) throws IOException {
        if (RegionFormatConfig.isReadOnlyMode()) {
            return;
        }

        int uncompressedSize = rawBuf.readableBytes();
        int maxCompressedLength = (int) Zstd.compressBound(uncompressedSize);
        ByteBuf compressedBuf = PooledByteBufAllocator.DEFAULT.directBuffer(maxCompressedLength);

        int compressedSize;
        int chunkCrc;

        try {
            ByteBuffer srcNio = rawBuf.nioBuffer(rawBuf.readerIndex(), uncompressedSize);
            ByteBuffer dstNio = compressedBuf.nioBuffer(0, maxCompressedLength);

            // Parallel Zstd compression outside region lock
            compressedSize = LinearV3ZstdContextPool.compress(
                dstNio, 0, maxCompressedLength,
                srcNio, 0, uncompressedSize,
                this.compressionLevel
            );

            // Hardware-accelerated CRC32C outside region lock
            dstNio.position(0);
            dstNio.limit(compressedSize);
            CRC32C crc = new CRC32C();
            crc.update(dstNio);
            chunkCrc = (int) crc.getValue();

            // Commit to disk under stamped lock
            commitCompressedChunk(pos, dstNio, compressedSize, uncompressedSize, chunkCrc);
        } finally {
            compressedBuf.release();
        }
    }

    private void commitCompressedChunk(
        ChunkPos pos,
        ByteBuffer compressedData,
        int compSize,
        int uncompSize,
        int chunkCrc
    ) throws IOException {
        int idx = getChunkIndex(pos.x, pos.z);
        long reservedOffset;
        int timestamp = (int) (System.currentTimeMillis() / 1000L);

        long stamp = this.lock.writeLock();
        try {
            if (this.closed.get()) {
                throw new ClosedChannelException();
            }

            int oldCompSize = this.chunkCompressedSizes[idx];
            if (oldCompSize > 0) {
                this.deadSpaceBytes += oldCompSize;
            } else {
                this.activeChunkCount++;
            }

            reservedOffset = this.fileEndOffset;
            this.fileEndOffset += compSize;

            this.chunkOffsets[idx] = (int) reservedOffset;
            this.chunkCompressedSizes[idx] = compSize;
            this.chunkUncompressedSizes[idx] = uncompSize;
            this.chunkCRC32Cs[idx] = chunkCrc;
            this.chunkTimestamps[idx] = timestamp;

            // 1. Positional write of compressed payload
            compressedData.position(0);
            compressedData.limit(compSize);
            writeFully(this.fileChannel, compressedData, reservedOffset);

            // 2. Write index slot (16 bytes) at 64 + idx * 16 using zero-allocation scratch direct buffer
            this.writeScratchBuf.clear();
            this.writeScratchBuf.putInt((int) reservedOffset);
            this.writeScratchBuf.putInt(compSize);
            this.writeScratchBuf.putInt(uncompSize);
            this.writeScratchBuf.putInt(chunkCrc);
            this.writeScratchBuf.flip();
            writeFully(this.fileChannel, this.writeScratchBuf, HEADER_SIZE + (long) idx * 16);

            // 3. Write timestamp slot (4 bytes) at 16448 + idx * 4
            this.writeScratchBuf.clear();
            this.writeScratchBuf.putInt(timestamp);
            this.writeScratchBuf.flip();
            writeFully(this.fileChannel, this.writeScratchBuf, HEADER_SIZE + INDEX_TABLE_SIZE + (long) idx * 4);

            // 4. Update Superblock active chunk count & dead space
            this.writeScratchBuf.clear();
            writeSuperblockToBuffer(this.writeScratchBuf, this.activeChunkCount, this.deadSpaceBytes);
            this.writeScratchBuf.position(0);
            this.writeScratchBuf.limit(HEADER_SIZE);
            writeFully(this.fileChannel, this.writeScratchBuf, 0);

            if (this.sync) {
                this.fileChannel.force(false);
            }

            // Check if compaction threshold exceeded
            if (shouldCompact()) {
                performCompaction();
            }
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    private boolean shouldCompact() {
        try {
            long fileSize = this.fileChannel.size();
            return this.deadSpaceBytes > 4L * 1024 * 1024 && this.deadSpaceBytes > (long) (fileSize * 0.35);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Performs zero-recompression vacuum compaction by sequentially copying active compressed chunk frames
     * into a temporary region file and atomically replacing the target file.
     */
    public void performCompaction() throws IOException {
        Path tmpPath = this.filePath.resolveSibling(this.filePath.getFileName().toString() + ".tmp");
        FileChannel tmpChannel = FileChannel.open(tmpPath,
            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

        int[] newOffsets = new int[1024];
        long newPayloadOffset = METADATA_TOTAL_SIZE;

        try {
            for (int i = 0; i < 1024; i++) {
                int off = this.chunkOffsets[i];
                int compSize = this.chunkCompressedSizes[i];

                if (off > 0 && compSize > 0) {
                    ByteBuf copyBuf = PooledByteBufAllocator.DEFAULT.directBuffer(compSize);
                    try {
                        ByteBuffer nio = copyBuf.nioBuffer(0, compSize);
                        readFully(this.fileChannel, nio, Integer.toUnsignedLong(off), compSize);

                        nio.position(0);
                        nio.limit(compSize);
                        CRC32C crc = new CRC32C();
                        crc.update(nio);
                        int check = (int) crc.getValue();
                        if (check != this.chunkCRC32Cs[i]) {
                            throw new IOException("Corrupted chunk detected during compaction at slot " + i +
                                " (expected 0x" + Integer.toHexString(this.chunkCRC32Cs[i]) + ", got 0x" + Integer.toHexString(check) + ")");
                        }

                        nio.position(0);
                        writeFully(tmpChannel, nio, newPayloadOffset);

                        newOffsets[i] = (int) newPayloadOffset;
                        newPayloadOffset += compSize;
                    } finally {
                        copyBuf.release();
                    }
                }
            }

            // Write packed metadata tables into tmpChannel
            ByteBuffer metaBuf = ByteBuffer.allocate(METADATA_TOTAL_SIZE);
            writeSuperblockToBuffer(metaBuf, this.activeChunkCount, 0L);

            metaBuf.position(HEADER_SIZE);
            for (int i = 0; i < 1024; i++) {
                metaBuf.putInt(newOffsets[i]);
                metaBuf.putInt(this.chunkCompressedSizes[i]);
                metaBuf.putInt(this.chunkUncompressedSizes[i]);
                metaBuf.putInt(this.chunkCRC32Cs[i]);
            }

            for (int i = 0; i < 1024; i++) {
                metaBuf.putInt(this.chunkTimestamps[i]);
            }

            metaBuf.position(0);
            metaBuf.limit(METADATA_TOTAL_SIZE);
            writeFully(tmpChannel, metaBuf, 0);

            tmpChannel.force(true);
        } finally {
            tmpChannel.close();
        }

        // Close old channel before rename (mandatory on Windows to avoid AccessDeniedException)
        this.fileChannel.close();

        try {
            try {
                Files.move(tmpPath, this.filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, this.filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable moveEx) {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Throwable ignored) {}

            if (!this.closed.get()) {
                try {
                    this.fileChannel = FileChannel.open(this.filePath, StandardOpenOption.READ, StandardOpenOption.WRITE);
                } catch (Throwable reopenEx) {
                    moveEx.addSuppressed(reopenEx);
                }
            }
            throw moveEx;
        }

        // Re-open fresh channel if region is still active
        if (!this.closed.get()) {
            this.fileChannel = FileChannel.open(this.filePath, StandardOpenOption.READ, StandardOpenOption.WRITE);
            System.arraycopy(newOffsets, 0, this.chunkOffsets, 0, 1024);
            this.deadSpaceBytes = 0;
            this.fileEndOffset = newPayloadOffset;
        }
    }

    @Override
    public void clear(ChunkPos pos) throws IOException {
        if (RegionFormatConfig.isReadOnlyMode()) {
            return;
        }

        int idx = getChunkIndex(pos.x, pos.z);
        long stamp = this.lock.writeLock();
        try {
            if (this.closed.get()) {
                throw new ClosedChannelException();
            }

            int oldCompSize = this.chunkCompressedSizes[idx];
            if (oldCompSize > 0) {
                this.deadSpaceBytes += oldCompSize;
                this.activeChunkCount = Math.max(0, this.activeChunkCount - 1);
            }

            this.chunkOffsets[idx] = 0;
            this.chunkCompressedSizes[idx] = 0;
            this.chunkUncompressedSizes[idx] = 0;
            this.chunkCRC32Cs[idx] = 0;
            this.chunkTimestamps[idx] = 0;

            // Zero out index slot (16 bytes)
            this.writeScratchBuf.clear();
            for (int k = 0; k < 4; k++) {
                this.writeScratchBuf.putInt(0);
            }
            this.writeScratchBuf.flip();
            writeFully(this.fileChannel, this.writeScratchBuf, HEADER_SIZE + (long) idx * 16);

            // Zero out timestamp slot (4 bytes)
            this.writeScratchBuf.clear();
            this.writeScratchBuf.putInt(0);
            this.writeScratchBuf.flip();
            writeFully(this.fileChannel, this.writeScratchBuf, HEADER_SIZE + INDEX_TABLE_SIZE + (long) idx * 4);

            // Update superblock
            this.writeScratchBuf.clear();
            writeSuperblockToBuffer(this.writeScratchBuf, this.activeChunkCount, this.deadSpaceBytes);
            this.writeScratchBuf.position(0);
            this.writeScratchBuf.limit(HEADER_SIZE);
            writeFully(this.fileChannel, this.writeScratchBuf, 0);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    @Override
    public void flush() throws IOException {
        long stamp = this.lock.readLock();
        try {
            if (!this.closed.get() && this.fileChannel != null && this.fileChannel.isOpen()) {
                this.fileChannel.force(true);
            }
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    @Override
    public void close() throws IOException {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        long stamp = this.lock.writeLock();
        try {
            if (this.deadSpaceBytes > 0 && this.activeChunkCount > 0 && shouldCompact()) {
                try {
                    performCompaction();
                    return;
                } catch (Throwable t) {
                    LOGGER.warn("Failed to perform compaction during close of {}: {}", this.filePath, t.getMessage());
                }
            }

            if (this.fileChannel != null && this.fileChannel.isOpen()) {
                this.fileChannel.force(true);
                this.fileChannel.close();
            }
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    @Override
    public CompoundTag getOversizedData(int x, int z) {
        return null; // Linear V3 natively handles up to 16 MB chunk payloads without external .mcc files
    }

    @Override
    public boolean isOversized(int x, int z) {
        return false;
    }

    @Override
    public boolean recalculateHeader() {
        return true;
    }

    @Override
    public void setOversized(int x, int z, boolean oversized) {}

    @Override
    public int getRecalculateCount() {
        return 0;
    }

    public int getActiveChunkCount() {
        long stamp = this.lock.tryOptimisticRead();
        int count = this.activeChunkCount;
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                count = this.activeChunkCount;
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return count;
    }

    public long getDeadSpaceBytes() {
        long stamp = this.lock.tryOptimisticRead();
        long dead = this.deadSpaceBytes;
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                dead = this.deadSpaceBytes;
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return dead;
    }

    private static void readFully(FileChannel channel, ByteBuffer dst, long position, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int read = channel.read(dst, position + totalRead);
            if (read < 0) {
                throw new EOFException("Unexpected EOF while reading from channel at position " + (position + totalRead));
            }
            totalRead += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer src, long position) throws IOException {
        long curPos = position;
        while (src.hasRemaining()) {
            int written = channel.write(src, curPos);
            curPos += written;
        }
    }

    /**
     * Netty-backed off-heap chunk stream to serialize chunk NBT directly into pooled direct memory.
     */
    private final class ChunkBuffer extends OutputStream {
        private final ChunkPos pos;
        private final ByteBuf rawBuf;
        private final ByteBufOutputStream out;
        private boolean closed = false;

        public ChunkBuffer(ChunkPos pos) {
            this.pos = pos;
            this.rawBuf = PooledByteBufAllocator.DEFAULT.directBuffer(INITIAL_WRITE_BUFFER_SIZE);
            this.out = new ByteBufOutputStream(this.rawBuf);
        }

        @Override
        public void write(int b) throws IOException {
            this.out.write(b);
        }

        @Override
        public void write(byte @NotNull [] b, int off, int len) throws IOException {
            this.out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            this.out.flush();
        }

        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try {
                this.out.close();
                compressAndCommit(this.pos, this.rawBuf);
            } finally {
                this.rawBuf.release();
            }
        }
    }
}
