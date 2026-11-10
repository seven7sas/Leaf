/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.async.collision;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Team;
import org.dreeam.leaf.config.modules.async.ParallelCollisions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

public final class ParallelCollisionDispatcher {

    public record CollisionTaskResult(
        LivingEntity entity,
        double pushX,
        double pushZ,
        int collisionCount,
        boolean crammingDamage
    ) {
    }

    public static final int THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 3, 2, 4);

    public static final ForkJoinPool COLLISION_POOL = new ForkJoinPool(
        THREADS,
        pool -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("Leaf-Collision-Worker-" + worker.getPoolIndex());
            worker.setDaemon(true);
            return worker;
        },
        null,
        false
    );

    private ParallelCollisionDispatcher() {
    }

    public static void tick(ServerLevel level, EntityTickList entityList) {
        // Fast-path fallback for low vCPU, disabled, or player-only push
        if (org.dreeam.leaf.config.modules.gameplay.OnlyPlayerPushable.enabled
            || !ParallelCollisions.enabled
            || Runtime.getRuntime().availableProcessors() <= 3) {
            return;
        }

        int maxCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        int maxCollisions = level.paperConfig().collisions.maxEntityCollisions;
        if (maxCramming <= 0 && maxCollisions <= 0) {
            return;
        }

        List<LivingEntity> pushables = new ArrayList<>();
        entityList.forEach(entity -> {
            if (entity instanceof LivingEntity living
                && living.isAlive()
                && !living.isRemoved()
                && !living.isSpectator()
                && !living.noPhysics
                && living.isPushable()
                && !living.isPassenger()
                && !level.tickRateManager().isEntityFrozen(living)) {
                Team team = living.getTeam();
                if (team != null && team.getCollisionRule() == Team.CollisionRule.NEVER) {
                    return;
                }
                pushables.add(living);
            }
        });

        int size = pushables.size();
        // Fast-path fallback for low entity count
        if (size < 64) {
            return;
        }

        CollisionTaskResult[] results = new CollisionTaskResult[size];
        long gameTime = level.getGameTime();

        // Phase 1: Parallel Read-Only Math Pass
        try {
            level.isParallelReadPassActive = true;
            COLLISION_POOL.submit(() -> {
                java.util.stream.IntStream.range(0, size).parallel().forEach(idx -> {
                    LivingEntity entity = pushables.get(idx);
                    // Mark handled immediately so pushEntities is never re-run synchronously even if nearby is empty
                    entity.parallelCollisionHandled = true;

                    AABB box = entity.getBoundingBox();
                    List<Entity> nearby = level.getPushableEntities(entity, box);
                    if (nearby.isEmpty()) {
                        results[idx] = null;
                        return;
                    }

                    double accX = 0;
                    double accZ = 0;
                    int collisions = 0;
                    boolean cramming = false;

                    // Cramming check
                    if (maxCramming > 0 && nearby.size() > maxCramming - 1) {
                        int nonPassengers = 0;
                        for (int i = 0; i < nearby.size(); i++) {
                            if (!nearby.get(i).isPassenger()) {
                                nonPassengers++;
                            }
                        }
                        if (nonPassengers > maxCramming - 1 && (entity.getId() + gameTime) % 4 == 0) {
                            cramming = true;
                        }
                    }

                    // Push displacement check
                    for (int i = 0; i < nearby.size(); i++) {
                        if (maxCollisions > 0 && collisions >= maxCollisions) {
                            break;
                        }
                        Entity target = nearby.get(i);
                        if (target.noPhysics || entity.isPassengerOfSameVehicle(target)) {
                            continue;
                        }

                        double dx = target.getX() - entity.getX();
                        double dz = target.getZ() - entity.getZ();
                        double max = Mth.absMax(dx, dz);

                        if (max >= 0.01D) {
                            double sqrt = Math.sqrt(max);
                            dx /= sqrt;
                            dz /= sqrt;
                            double d2 = Math.min(1.0D, 1.0D / sqrt);
                            // When target is LivingEntity, in vanilla both A and B push each other (2x impulse)
                            double multiplier = (target instanceof LivingEntity) ? 0.10D : 0.05D;
                            dx *= d2 * multiplier;
                            dz *= d2 * multiplier;

                            accX -= dx;
                            accZ -= dz;
                            collisions++;
                        }
                    }

                    results[idx] = new CollisionTaskResult(entity, accX, accZ, collisions, cramming);
                });
            }).join();
        } finally {
            level.isParallelReadPassActive = false;
        }

        // Phase 2: Sequential Main Thread Commit
        for (int i = 0; i < size; i++) {
            CollisionTaskResult res = results[i];
            if (res == null) continue;
            LivingEntity entity = res.entity;
            if (entity.isRemoved()) continue;

            if (res.pushX != 0 || res.pushZ != 0) {
                entity.push(res.pushX, 0, res.pushZ);
            }
            entity.numCollisions = res.collisionCount;

            if (res.crammingDamage) {
                entity.hurtServer(level, level.damageSources().cramming(), 6.0F);
            }
        }
    }

    public static void shutdown() {
        if (COLLISION_POOL != null && !COLLISION_POOL.isShutdown()) {
            COLLISION_POOL.shutdown();
            try {
                COLLISION_POOL.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
