/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.world.region.linear;

import abomination.IRegionFile;
import abomination.LinearRegionFile;
import me.earthme.luminol.utils.RegionCreatorInfo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.dreeam.leaf.config.modules.misc.RegionFormatConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Auto-detection and transparent migration helper for converting legacy Linear (v1/v2) region files
 * into the modern zero-leak Linear V3 format on the fly.
 */
public final class LinearV3MigrationHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinearV3MigrationHelper.class);

    private static final long LEGACY_LINEAR_MAGIC = 0xc3ff13183cca9d9aL;

    private LinearV3MigrationHelper() {}

    /**
     * Inspects magic header of existing region file and instantiates either LinearV3RegionFile
     * or transparently migrates legacy Linear V1/V2 files into Linear V3.
     */
    public static IRegionFile createOrMigrate(RegionCreatorInfo info) throws IOException {
        Path filePath = info.filePath();
        int compressionLevel = RegionFormatConfig.compressionLevel;

        if (!Files.exists(filePath) || Files.size(filePath) < 8) {
            String fileName = filePath.getFileName().toString();
            String mcaFileName = fileName.endsWith(".linear")
                ? fileName.substring(0, fileName.length() - ".linear".length()) + ".mca"
                : fileName + ".mca";
            Path mcaPath = filePath.resolveSibling(mcaFileName);
            if (Files.exists(mcaPath) && Files.size(mcaPath) > 0) {
                LOGGER.info("Detected legacy Anvil (.mca) format in {}. Starting transparent migration to Linear V3...", mcaFileName);
                try {
                    return migrateMcaToV3(info, mcaPath, compressionLevel);
                } catch (Throwable t) {
                    LOGGER.error("Failed to migrate Anvil region {} to Linear V3, falling back to original .mca: {}", mcaPath, t.getMessage(), t);
                    try {
                        Files.deleteIfExists(filePath);
                    } catch (Throwable ignored) {}
                    return new RegionFile(info.info(), mcaPath, info.folder(), info.sync());
                }
            }

            return new LinearV3RegionFile(info.info(), filePath, info.folder(), info.sync(), compressionLevel);
        }

        long magic = readMagic(filePath);
        if (magic == LinearV3RegionFile.SUPERBLOCK_MAGIC) {
            return new LinearV3RegionFile(info.info(), filePath, info.folder(), info.sync(), compressionLevel);
        }

        if (magic == LEGACY_LINEAR_MAGIC) {
            LOGGER.info("Detected legacy Linear (v1/v2) format in {}. Starting transparent migration to Linear V3...", filePath.getFileName());
            try {
                return migrateLegacyToV3(info, compressionLevel);
            } catch (Throwable t) {
                LOGGER.error("Failed to migrate legacy Linear region {} to Linear V3, falling back to legacy driver: {}", filePath, t.getMessage(), t);
                return new LinearRegionFile(info.info(), filePath, info.folder(), info.sync(), compressionLevel);
            }
        }

        // Unknown or new, attempt Linear V3
        return new LinearV3RegionFile(info.info(), filePath, info.folder(), info.sync(), compressionLevel);
    }

    private static long readMagic(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(8);
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) {
                    break;
                }
            }
            buf.flip();
            return buf.remaining() >= 8 ? buf.getLong() : 0L;
        }
    }

    private static IRegionFile migrateLegacyToV3(RegionCreatorInfo info, int compressionLevel) throws IOException {
        Path filePath = info.filePath();
        Path tmpPath = filePath.resolveSibling(filePath.getFileName().toString() + ".v3migration.tmp");

        Files.deleteIfExists(tmpPath);

        int regionX = 0;
        int regionZ = 0;
        String name = filePath.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length >= 3) {
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {}
        }

        LinearRegionFile legacy = new LinearRegionFile(info.info(), filePath, info.folder(), info.sync(), compressionLevel);
        LinearV3RegionFile v3Target = null;
        int migratedChunks = 0;

        try {
            v3Target = new LinearV3RegionFile(info.info(), tmpPath, info.folder(), info.sync(), compressionLevel);
            byte[] transferBuffer = new byte[8192];

            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    ChunkPos pos = new ChunkPos((regionX << 5) + x, (regionZ << 5) + z);
                    try {
                        if (legacy.hasChunk(pos)) {
                            try (DataInputStream in = legacy.getChunkDataInputStream(pos);
                                 DataOutputStream out = v3Target.getChunkDataOutputStream(pos)) {
                                if (in != null) {
                                    int read;
                                    while ((read = in.read(transferBuffer)) != -1) {
                                        out.write(transferBuffer, 0, read);
                                    }
                                    migratedChunks++;
                                }
                            }
                        }
                    } catch (Throwable chunkEx) {
                        LOGGER.warn("Corrupted or unreadable chunk {} in {}: {}, skipping chunk to preserve remaining region chunks",
                            pos, filePath.getFileName(), chunkEx.getMessage());
                    }
                }
            }

            v3Target.flush();
            v3Target.close();
            v3Target = null;
            legacy.close();
            legacy = null;

            try {
                Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            LOGGER.info("Successfully migrated {} chunks from legacy Linear to Linear V3 in {}", migratedChunks, filePath.getFileName());
            return new LinearV3RegionFile(info.info(), filePath, info.folder(), info.sync(), compressionLevel);
        } finally {
            if (v3Target != null) {
                try { v3Target.close(); } catch (Throwable ignored) {}
            }
            if (legacy != null) {
                try { legacy.close(); } catch (Throwable ignored) {}
            }
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Throwable ignored) {}
        }
    }

    private static IRegionFile migrateMcaToV3(RegionCreatorInfo info, Path mcaPath, int compressionLevel) throws IOException {
        Path filePath = info.filePath();
        Path tmpPath = filePath.resolveSibling(filePath.getFileName().toString() + ".v3migration.tmp");

        Files.deleteIfExists(tmpPath);

        int regionX = 0;
        int regionZ = 0;
        String mcaName = mcaPath.getFileName().toString();
        String[] parts = mcaName.split("\\.");
        if (parts.length >= 3) {
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {}
        }

        RegionFile mca = new RegionFile(info.info(), mcaPath, info.folder(), info.sync());
        LinearV3RegionFile v3Target = null;
        int migratedChunks = 0;

        try {
            v3Target = new LinearV3RegionFile(info.info(), tmpPath, info.folder(), info.sync(), compressionLevel);
            byte[] transferBuffer = new byte[16384];

            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    ChunkPos pos = new ChunkPos((regionX << 5) + x, (regionZ << 5) + z);
                    try {
                        if (mca.hasChunk(pos)) {
                            try (DataInputStream in = mca.getChunkDataInputStream(pos);
                                 DataOutputStream out = v3Target.getChunkDataOutputStream(pos)) {
                                if (in != null) {
                                    int read;
                                    while ((read = in.read(transferBuffer)) != -1) {
                                        out.write(transferBuffer, 0, read);
                                    }
                                    migratedChunks++;
                                }
                            }
                        }
                    } catch (Throwable chunkEx) {
                        LOGGER.warn("Corrupted or unreadable chunk {} in {}: {}, skipping chunk to preserve remaining region chunks",
                            pos, mcaName, chunkEx.getMessage());
                    }
                }
            }

            v3Target.flush();
            v3Target.close();
            v3Target = null;
            mca.close();
            mca = null;

            try {
                Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Safely rename old .mca file to .mca.bak to preserve backup and avoid re-triggering migration
            Path mcaBak = mcaPath.resolveSibling(mcaName + ".bak");
            try {
                Files.deleteIfExists(mcaBak);
                Files.move(mcaPath, mcaBak, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable bakEx) {
                LOGGER.warn("Failed to rename {} to backup: {}", mcaName, bakEx.getMessage());
            }

            // Also rename any matching oversized chunk (.mcc) files
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    int chunkX = (regionX << 5) + x;
                    int chunkZ = (regionZ << 5) + z;
                    Path mccPath = info.folder().resolve("c." + chunkX + "." + chunkZ + ".mcc");
                    if (Files.exists(mccPath)) {
                        try {
                            Path mccBak = mccPath.resolveSibling(mccPath.getFileName().toString() + ".bak");
                            Files.deleteIfExists(mccBak);
                            Files.move(mccPath, mccBak, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Throwable mccEx) {
                            LOGGER.warn("Failed to rename oversized chunk {} to backup: {}", mccPath.getFileName(), mccEx.getMessage());
                        }
                    }
                }
            }

            LOGGER.info("Successfully migrated {} chunks from Anvil (.mca) to Linear V3 in {}", migratedChunks, filePath.getFileName());
            return new LinearV3RegionFile(info.info(), filePath, info.folder(), info.sync(), compressionLevel);
        } finally {
            if (v3Target != null) {
                try { v3Target.close(); } catch (Throwable ignored) {}
            }
            if (mca != null) {
                try { mca.close(); } catch (Throwable ignored) {}
            }
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Throwable ignored) {}
        }
    }
}
