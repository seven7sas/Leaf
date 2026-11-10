/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.config.modules.async;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ParallelBrainSensing extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.ASYNC.getBaseKeyName() + ".parallel-brain-sensing";
    }

    public static boolean enabled = true;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath(), enabled,
            config.pickStringRegionBased(
                """
                    Whether to enable off-thread parallel sensing for mob brains when entity count is high.""",
                """
                    当实体数量较多时，是否开启多线程并发大脑感知计算。"""));
    }
}
