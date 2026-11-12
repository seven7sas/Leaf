/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class LaggingEntityGuard extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".lagging-entity-guard";
    }

    public static boolean enabled = true;
    public static int minEntitiesInChunk = 25;
    public static long maxEntityTickNanos = 4_000_000L;
    public static int suspectStrikes = 2;
    public static int requiredStrikes = 8;
    public static long maxChunkEntityTickNanos = 10_000_000L;
    public static int requiredChunkStrikes = 6;
    public static int maxItemsInChunkBeforeCull = 150;
    public static boolean logRemovals = true;

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                Automatically detects and removes persistently lagging entities and chunk-level lag machines
                using two-stage ThreadMXBean CPU verification while protecting players, items, bosses, pets, and NPCs.""",
            """
                使用两阶段 ThreadMXBean CPU 校验自动检测并清理持续卡顿的实体与区块卡服机，
                同时保护玩家、掉落物、Boss、宠物及自定义 NPC。""");

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled);
        minEntitiesInChunk = Math.max(1, config.getInt(getBasePath() + ".min-entities-in-chunk", minEntitiesInChunk,
            config.pickStringRegionBased(
                "Minimum total entities in a chunk before entity/chunk lag strikes can be accrued (default 25).",
                "区块内至少存在多少实体时才开始累计卡顿计数（默认 25）。")));
        maxEntityTickNanos = Math.max(1L, config.getLong(getBasePath() + ".max-entity-tick-nanos", maxEntityTickNanos,
            config.pickStringRegionBased(
                "Single-entity tick duration threshold in nanoseconds before accruing a lag strike (default 4.0 ms).",
                "单个实体单次 tick 超过该耗时（纳秒）时累计一次卡顿计数（默认 4.0 毫秒）。")));
        suspectStrikes = Math.max(0, config.getInt(getBasePath() + ".suspect-strikes", suspectStrikes,
            config.pickStringRegionBased(
                "Consecutive strikes after which an entity becomes suspect and is measured via ThreadMXBean CPU time (default 2).",
                "连续累计多少次计数后将实体标记为嫌疑并启用 ThreadMXBean CPU 时间精准校验（默认 2）。")));
        requiredStrikes = Math.max(1, config.getInt(getBasePath() + ".required-strikes", requiredStrikes,
            config.pickStringRegionBased(
                "Consecutive strikes required before removing an unprotected lagging entity (default 8).",
                "未受保护的实体连续多少 tick 超过阈值且通过 CPU 校验后将被清理（默认 8）。")));
        maxChunkEntityTickNanos = Math.max(1L, config.getLong(getBasePath() + ".max-chunk-entity-tick-nanos", maxChunkEntityTickNanos,
            config.pickStringRegionBased(
                "Total entity tick budget per chunk in nanoseconds before accruing a chunk strike (default 10.0 ms).",
                "单个区块内所有实体总耗时阈值（纳秒），超过时累计区块卡顿计数（默认 10.0 毫秒）。")));
        requiredChunkStrikes = Math.max(1, config.getInt(getBasePath() + ".required-chunk-strikes", requiredChunkStrikes,
            config.pickStringRegionBased(
                "Consecutive ticks a dense chunk must exceed max-chunk-entity-tick-nanos before priority culling (default 6).",
                "高密度区块连续多少 tick 超过总耗时阈值后触发优先级实体清理（默认 6）。")));
        maxItemsInChunkBeforeCull = Math.max(0, config.getInt(getBasePath() + ".max-items-in-chunk-before-cull", maxItemsInChunkBeforeCull,
            config.pickStringRegionBased(
                "ItemEntity count threshold in a chunk below which dropped items are spared during chunk culling (default 150).",
                "触发区块清理时，掉落物数量低于该阈值将完全保留不会被清理（默认 150）。")));
        logRemovals = config.getBoolean(getBasePath() + ".log-removals", logRemovals);
    }
}
