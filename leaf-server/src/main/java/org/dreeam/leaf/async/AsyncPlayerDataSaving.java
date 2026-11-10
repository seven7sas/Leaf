package org.dreeam.leaf.async;

import org.dreeam.leaf.config.modules.async.AsyncPlayerDataSave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AsyncPlayerDataSaving {

    public static volatile ExecutorService IO_POOL = null;
    private static final ConcurrentHashMap<UUID, CompletableFuture<Void>> PER_PLAYER_LOCKS = new ConcurrentHashMap<>();
    private static final AtomicReference<CompletableFuture<Void>> LEVEL_DATA_LOCK = new AtomicReference<>(CompletableFuture.completedFuture(null));

    private AsyncPlayerDataSaving() {
    }

    public static synchronized void init() {
        if (IO_POOL == null || IO_POOL.isShutdown()) {
            IO_POOL = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("Leaf-PlayerData-VT-", 0).factory()
            );
        }
    }

    public static CompletableFuture<Void> submitPlayerSave(UUID uuid, Runnable runnable) {
        if (!AsyncPlayerDataSave.enabled || IO_POOL == null || IO_POOL.isShutdown()) {
            runnable.run();
            return CompletableFuture.completedFuture(null);
        }
        if (uuid == null) {
            submit(runnable);
            return CompletableFuture.completedFuture(null);
        }

        return PER_PLAYER_LOCKS.compute(uuid, (key, currentFuture) -> {
            CompletableFuture<Void> previous = (currentFuture == null)
                ? CompletableFuture.completedFuture(null)
                : currentFuture;

            final AtomicReference<CompletableFuture<Void>> nextRef = new AtomicReference<>();
            CompletableFuture<Void> next = previous.handleAsync((res, ex) -> {
                try {
                    runnable.run();
                } finally {
                    PER_PLAYER_LOCKS.computeIfPresent(uuid, (u, f) -> f == nextRef.get() ? null : f);
                }
                return null;
            }, IO_POOL);
            nextRef.set(next);

            return next;
        });
    }

    public static void submitLevelSave(Runnable runnable) {
        if (!AsyncPlayerDataSave.enabled || IO_POOL == null || IO_POOL.isShutdown()) {
            runnable.run();
            return;
        }

        LEVEL_DATA_LOCK.updateAndGet(prev ->
            (prev == null ? CompletableFuture.completedFuture(null) : prev)
                .handleAsync((res, ex) -> {
                    runnable.run();
                    return null;
                }, IO_POOL)
        );
    }

    public static void awaitPendingSave(UUID uuid) {
        CompletableFuture<Void> future = PER_PLAYER_LOCKS.get(uuid);
        if (future != null) {
            try {
                future.get(10L, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    public static void awaitAll() {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            CompletableFuture<?>[] playerFutures = PER_PLAYER_LOCKS.values().toArray(new CompletableFuture[0]);
            CompletableFuture<Void> levelFuture = LEVEL_DATA_LOCK.get();

            List<CompletableFuture<?>> all = new ArrayList<>(playerFutures.length + 1);
            Collections.addAll(all, playerFutures);
            if (levelFuture != null) {
                all.add(levelFuture);
            }

            if (all.isEmpty()) {
                break;
            }

            try {
                CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).get(
                    Math.max(100L, deadline - System.currentTimeMillis()),
                    TimeUnit.MILLISECONDS
                );
                break;
            } catch (Exception ignored) {
            }

            if (PER_PLAYER_LOCKS.isEmpty()) {
                break;
            }
        }
    }

    public static Optional<java.util.concurrent.Future<?>> submit(Runnable runnable) {
        if (!AsyncPlayerDataSave.enabled || IO_POOL == null || IO_POOL.isShutdown()) {
            runnable.run();
            return Optional.empty();
        } else {
            return Optional.of(IO_POOL.submit(runnable));
        }
    }
}
