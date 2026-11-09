package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class OptimizePlayerMovementProcessing extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName();
    }

    public static boolean enabled = true;
    public static boolean skipEventOnRotationOnly = true;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath() + ".optimize-player-movement", enabled, config.pickStringRegionBased("""
                Whether to optimize player movement processing by skipping unnecessary edge checks and avoiding redundant view distance updates.""",
            """
                是否优化玩家移动处理，跳过不必要的边缘检查并避免冗余的视距更新。"""));
        skipEventOnRotationOnly = config.getBoolean(getBasePath() + ".skip-move-event-on-rotation-only", skipEventOnRotationOnly, config.pickStringRegionBased("""
                Whether to skip calling PlayerMoveEvent when the player only rotated their camera without positional movement.""",
            """
                当玩家仅旋转视角而没有位置位移时，是否跳过调用 PlayerMoveEvent。"""));
    }
}
