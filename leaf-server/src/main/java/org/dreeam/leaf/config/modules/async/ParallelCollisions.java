/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.config.modules.async;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ParallelCollisions extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.ASYNC.getBaseKeyName() + ".parallel-collisions";
    }

    public static boolean enabled = true;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath(), enabled,
            config.pickStringRegionBased(
                """
                    Whether to enable split-phase parallel entity push collisions when entity count is high.""",
                """
                    当实体数量较多时，是否开启双阶段并行实体推挤计算。"""));
    }
}
