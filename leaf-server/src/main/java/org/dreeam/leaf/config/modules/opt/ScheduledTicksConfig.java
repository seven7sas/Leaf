package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ScheduledTicksConfig extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName();
    }

    public static int maxScheduledTicksPerChunkPerTick = 256;

    @Override
    public void onLoaded() {
        maxScheduledTicksPerChunkPerTick = config.getInt(
            getBasePath() + ".max-scheduled-ticks-per-chunk-per-tick",
            maxScheduledTicksPerChunkPerTick,
            config.pickStringRegionBased(
                """
                    Maximum number of scheduled ticks drained from a single chunk per game tick.
                    Remaining ticks stay queued and are processed on subsequent ticks.
                    Set to <= 0 to disable per-chunk fairness throttling.""",
                """
                    每个游戏刻从单个区块最多排队的计划刻数量。
                    超出部分会保留在队列中并在后续刻处理。
                    设为 <= 0 可禁用此分块限流。"""
            )
        );
    }
}
