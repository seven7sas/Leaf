/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.async.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.level.entity.EntityTickList;
import org.dreeam.leaf.config.modules.async.ParallelBrainSensing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

public final class ParallelBrainSensingDispatcher {

    public static final int THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 3, 2, 4);

    public static final ForkJoinPool BRAIN_POOL = new ForkJoinPool(
        THREADS,
        pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("Leaf-Brain-Worker-" + worker.getPoolIndex());
            worker.setDaemon(true);
            return worker;
        },
        null,
        false
    );

    private ParallelBrainSensingDispatcher() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void tick(ServerLevel level, EntityTickList entityList) {
        // Fast-path fallback for low vCPU or disabled
        if (!ParallelBrainSensing.enabled || Runtime.getRuntime().availableProcessors() <= 3) {
            return;
        }

        List<LivingEntity> brainEntities = new ArrayList<>();
        entityList.forEach(entity -> {
            if (entity instanceof LivingEntity living
                && living.isAlive()
                && !living.isRemoved()
                && living.isEffectiveAi()
                && !level.tickRateManager().isEntityFrozen(living)
                && living.getBrain().hasSensors()) {
                brainEntities.add(living);
            }
        });

        int size = brainEntities.size();
        // Fast-path fallback for low entity count
        if (size < 64) {
            return;
        }

        // Phase 1: Off-Thread Parallel Sensing Pass with safe parallel read pass active
        try {
            level.isParallelReadPassActive = true;
            BRAIN_POOL.submit(() -> {
                java.util.stream.IntStream.range(0, size).parallel().forEach(idx -> {
                    LivingEntity entity = brainEntities.get(idx);
                    Brain brain = entity.getBrain();
                    brain.isStagingMemories = true;
                    brain.tickSensorsExternal(level, entity);
                });
            }).join();
        } finally {
            level.isParallelReadPassActive = false;
        }

        // Phase 2: Sequential Main Thread Commit
        for (int i = 0; i < size; i++) {
            LivingEntity entity = brainEntities.get(i);
            if (entity.isRemoved()) continue;
            entity.getBrain().commitStagedMemories();
        }
    }

    public static void shutdown() {
        if (BRAIN_POOL != null && !BRAIN_POOL.isShutdown()) {
            BRAIN_POOL.shutdown();
            try {
                BRAIN_POOL.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
