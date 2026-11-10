/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ContainerItemLazyLoading extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".container-item-lazy-loading";
    }

    public static boolean lazyContainerItemLoading = false;

    @Override
    public void onLoaded() {
        lazyContainerItemLoading = config.getBoolean(getBasePath(), lazyContainerItemLoading,
            config.pickStringRegionBased(
                """
                    Experimental: Defer item decoding in chests, trapped chests, barrels and shulker boxes until inventory access.
                    Retains the block entity's raw item NBT data until decoding, may increase memory usage.""",
                """
                    实验性功能: 箱子、陷阱箱、木桶和潜影盒延迟到访问时解码物品,
                    解码前保留方块实体的原始物品 NBT 数据, 可能增加内存占用."""));
    }
}
