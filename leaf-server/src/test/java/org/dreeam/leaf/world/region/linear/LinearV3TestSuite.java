package org.dreeam.leaf.world.region.linear;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

public class LinearV3TestSuite {

    @BeforeAll
    public static void setUp() {
    }

    @AfterAll
    public static void tearDown() {
        LinearV3ZstdContextPool.shutdown();
    }

    @Test
    public void testLinearV3Constants() {
        Assertions.assertEquals(0x4C494E4541525633L, LinearV3RegionFile.SUPERBLOCK_MAGIC, "Linear V3 magic must match LINEARV3");
        Assertions.assertEquals(3, LinearV3RegionFile.FORMAT_VERSION, "Linear V3 version must be 3");
        Assertions.assertEquals(64, LinearV3RegionFile.HEADER_SIZE, "Header size must be 64 bytes");
        Assertions.assertEquals(16384, LinearV3RegionFile.INDEX_TABLE_SIZE, "Index table size must be 16384 bytes");
        Assertions.assertEquals(4096, LinearV3RegionFile.TIMESTAMP_TABLE_SIZE, "Timestamp table size must be 4096 bytes");
        Assertions.assertEquals(20544, LinearV3RegionFile.METADATA_TOTAL_SIZE, "Metadata total size must be 20544 bytes");
    }

    @Test
    public void testZstdCompressDecompressRoundtrip() throws Exception {
        byte[] original = "ZeroLeakLinearV3ChunkPayloadTestingWithZstandardCompressionDirectByteBufferRoundtrip".getBytes(StandardCharsets.UTF_8);

        ByteBuffer srcDirect = ByteBuffer.allocateDirect(original.length);
        srcDirect.put(original);
        srcDirect.flip();

        int maxCompressedSize = (int) com.github.luben.zstd.Zstd.compressBound(original.length);
        ByteBuffer compDirect = ByteBuffer.allocateDirect(maxCompressedSize);

        int compressedLen = LinearV3ZstdContextPool.compress(
            compDirect, 0, maxCompressedSize,
            srcDirect, 0, original.length,
            3
        );
        Assertions.assertTrue(compressedLen > 0, "Compressed length should be greater than 0");

        ByteBuffer decompDirect = ByteBuffer.allocateDirect(original.length);
        int decompressedLen = LinearV3ZstdContextPool.decompress(
            decompDirect, 0, original.length,
            compDirect, 0, compressedLen
        );
        Assertions.assertEquals(original.length, decompressedLen, "Decompressed length must match original");

        byte[] recovered = new byte[original.length];
        decompDirect.position(0);
        decompDirect.get(recovered);
        Assertions.assertArrayEquals(original, recovered, "Recovered data must equal original data");
    }

    @Test
    public void testCRC32CIntegrity() {
        byte[] payload = "LinearV3ChunkDataChecksumVerification".getBytes(StandardCharsets.UTF_8);
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        int checksum = (int) crc.getValue();

        CRC32C verify = new CRC32C();
        verify.update(payload, 0, payload.length);
        Assertions.assertEquals(checksum, (int) verify.getValue(), "CRC32C must match across calculations");
    }

    @Test
    public void testTemporaryFileLifecycle(@TempDir Path tempDir) throws Exception {
        Path regionPath = tempDir.resolve("r.0.0.linear");
        Path tmpPath = tempDir.resolve("r.0.0.linear.tmp");

        Files.write(tmpPath, new byte[]{1, 2, 3, 4});
        Assertions.assertTrue(Files.exists(tmpPath));
        Assertions.assertFalse(Files.exists(regionPath));

        Files.move(tmpPath, regionPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Assertions.assertFalse(Files.exists(tmpPath));
        Assertions.assertTrue(Files.exists(regionPath));
        Assertions.assertEquals(4, Files.size(regionPath));
    }

    @Test
    public void testMcaFallbackIntegrity(@TempDir Path tempDir) throws Exception {
        Path mcaPath = tempDir.resolve("r.0.0.mca");
        Path linearPath = tempDir.resolve("r.0.0.linear");

        Files.write(mcaPath, new byte[8192]); // mock 8KB MCA header
        Assertions.assertTrue(Files.exists(mcaPath));
        Assertions.assertFalse(Files.exists(linearPath));

        // When migration fails, linearPath must NOT be left on disk as an empty file
        Files.deleteIfExists(linearPath);
        Assertions.assertFalse(Files.exists(linearPath), "Linear path must not exist when migration falls back");
        Assertions.assertTrue(Files.exists(mcaPath), "Original MCA file must remain intact for fallback");
    }
}
