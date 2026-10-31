package org.dreeam.leaf.world.region.linear;

import com.github.luben.zstd.*;
import io.netty.util.concurrent.FastThreadLocal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

/**
 * High-performance, zero-leak JNI context manager for Zstandard compression and decompression.
 * Uses FastThreadLocal to eliminate allocation overhead and lock contention on Moonrise I/O threads,
 * with deterministic native memory reclamation upon thread termination or JVM shutdown.
 */
public final class LinearV3ZstdContextPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinearV3ZstdContextPool.class);

    private static final Set<AutoCloseable> ACTIVE_CONTEXTS = ConcurrentHashMap.newKeySet();

    private static volatile ZstdDictCompress staticDictCompress = null;
    private static volatile ZstdDictDecompress staticDictDecompress = null;
    private static volatile int dictionaryHash = 0;

    private static final FastThreadLocal<ZstdCompressCtx> COMPRESS_POOL = new FastThreadLocal<>() {
        @Override
        protected ZstdCompressCtx initialValue() {
            ZstdCompressCtx ctx = new ZstdCompressCtx();
            ACTIVE_CONTEXTS.add(ctx);
            return ctx;
        }

        @Override
        protected void onRemoval(ZstdCompressCtx ctx) {
            ACTIVE_CONTEXTS.remove(ctx);
            ctx.close();
        }
    };

    private static final FastThreadLocal<ZstdDecompressCtx> DECOMPRESS_POOL = new FastThreadLocal<>() {
        @Override
        protected ZstdDecompressCtx initialValue() {
            ZstdDecompressCtx ctx = new ZstdDecompressCtx();
            ACTIVE_CONTEXTS.add(ctx);
            return ctx;
        }

        @Override
        protected void onRemoval(ZstdDecompressCtx ctx) {
            ACTIVE_CONTEXTS.remove(ctx);
            ctx.close();
        }
    };

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(LinearV3ZstdContextPool::shutdown, "LinearV3-ZstdContextPool-Shutdown"));
    }

    private LinearV3ZstdContextPool() {}

    /**
     * Compresses data between direct ByteBuffers using pooled native ZstdCompressCtx.
     */
    public static int compress(
        ByteBuffer dst, int dstOffset, int dstCapacity,
        ByteBuffer src, int srcOffset, int srcLength,
        int compressionLevel
    ) throws IOException {
        ZstdCompressCtx ctx = COMPRESS_POOL.get();
        ctx.reset();
        ctx.setLevel(compressionLevel);
        ctx.setContentSize(true);

        ZstdDictCompress dict = staticDictCompress;
        if (dict != null) {
            ctx.loadDict(dict);
        }

        int written = ctx.compressDirectByteBuffer(dst, dstOffset, dstCapacity, src, srcOffset, srcLength);
        if (Zstd.isError(written)) {
            throw new IOException("Zstd compression error: " + Zstd.getErrorName(written));
        }
        return written;
    }

    /**
     * Decompresses data between direct ByteBuffers using pooled native ZstdDecompressCtx.
     */
    public static int decompress(
        ByteBuffer dst, int dstOffset, int dstCapacity,
        ByteBuffer src, int srcOffset, int srcLength
    ) throws IOException {
        ZstdDecompressCtx ctx = DECOMPRESS_POOL.get();
        ctx.reset();

        ZstdDictDecompress dict = staticDictDecompress;
        if (dict != null) {
            ctx.loadDict(dict);
        }

        int decompressed = ctx.decompressDirectByteBuffer(dst, dstOffset, dstCapacity, src, srcOffset, srcLength);
        if (Zstd.isError(decompressed)) {
            throw new IOException("Zstd decompression error: " + Zstd.getErrorName(decompressed));
        }
        return decompressed;
    }

    /**
     * Loads an optional pre-trained NBT dictionary as an immutable static singleton.
     */
    public static synchronized void loadDictionary(byte[] dictBytes, int compressionLevel) {
        closeDictionaries();
        if (dictBytes != null && dictBytes.length > 0) {
            staticDictCompress = new ZstdDictCompress(dictBytes, compressionLevel);
            staticDictDecompress = new ZstdDictDecompress(dictBytes);
            CRC32C crc = new CRC32C();
            crc.update(dictBytes);
            dictionaryHash = (int) crc.getValue();
            LOGGER.info("Linear V3 loaded pre-trained Zstd dictionary ({} bytes, CRC32C=0x{})",
                dictBytes.length, Integer.toHexString(dictionaryHash));
        }
    }

    public static int getDictionaryHash() {
        return dictionaryHash;
    }

    private static synchronized void closeDictionaries() {
        if (staticDictCompress != null) {
            staticDictCompress.close();
            staticDictCompress = null;
        }
        if (staticDictDecompress != null) {
            staticDictDecompress.close();
            staticDictDecompress = null;
        }
        dictionaryHash = 0;
    }

    /**
     * Explicit shutdown hook to release all pooled native JNI contexts and dictionaries.
     */
    public static synchronized void shutdown() {
        closeDictionaries();
        for (AutoCloseable ctx : ACTIVE_CONTEXTS) {
            try {
                ctx.close();
            } catch (Throwable ignored) {}
        }
        ACTIVE_CONTEXTS.clear();
        COMPRESS_POOL.remove();
        DECOMPRESS_POOL.remove();
    }
}
