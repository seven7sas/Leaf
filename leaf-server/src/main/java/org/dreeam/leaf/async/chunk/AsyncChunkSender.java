package org.dreeam.leaf.async.chunk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dreeam.leaf.util.queue.MpmcQueue;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@NullMarked
public final class AsyncChunkSender {

    private static final Logger LOGGER = LogManager.getLogger("Leaf");
    private static final int CAPACITY = 255;

    private final MpmcQueue<ClientboundLevelChunkWithLightPacket> channel;
    private final LongOpenHashSet pending;
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger generation = new AtomicInteger();

    public AsyncChunkSender() {
        this.channel = new MpmcQueue<>(ClientboundLevelChunkWithLightPacket.class, CAPACITY);
        this.pending = new LongOpenHashSet();
    }

    public boolean add(long k) {
        return size.get() < CAPACITY && pending.size() < CAPACITY && pending.add(k);
    }

    public boolean remove(long k) {
        return pending.remove(k);
    }

    public boolean contains(long k) {
        return pending.contains(k);
    }

    public void clear() {
        this.generation.incrementAndGet();
        pending.clear();
        while (recv() != null) ;
    }

    public void submit(Supplier<ClientboundLevelChunkWithLightPacket> task) {
        size.incrementAndGet();
        final int gen = this.generation.get();
        AsyncChunkSend.POOL.submit(() -> {
            try {
                if (this.generation.get() != gen) {
                    size.decrementAndGet();
                    return;
                }
                ClientboundLevelChunkWithLightPacket chunk = task.get();
                if (chunk == null || this.generation.get() != gen) {
                    size.decrementAndGet();
                    return;
                }
                while (!channel.send(chunk)) {
                    if (this.generation.get() != gen) {
                        size.decrementAndGet();
                        return;
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(50_000L); // 50 us
                }
            } catch (Throwable t) {
                size.decrementAndGet();
                LOGGER.error("Failed to prepare async chunk packet", t);
            }
        });
    }

    public @Nullable ClientboundLevelChunkWithLightPacket recv() {
        final ClientboundLevelChunkWithLightPacket chunk = this.channel.recv();
        if (chunk != null) {
            size.decrementAndGet();
        }
        return chunk;
    }
}
