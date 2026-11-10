/*
 * Copyright (c) 2026 seven7sas (Fork Leaf - https://github.com/seven7sas/Leaf)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class PredictiveChunkConfig extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".chunk-loading";
    }

    public static boolean predictiveVelocityConeSend = true;
    public static boolean smoothSpawnRampUp = true;

    @Override
    public void onLoaded() {
        predictiveVelocityConeSend = config.getBoolean(getBasePath() + ".predictive-velocity-cone-send", predictiveVelocityConeSend,
            config.pickStringRegionBased(
                "Prioritize sending chunks in the direction the player is moving (velocity cone).",
                "是否根据玩家移动速度向量（速度锥）优先发送前方的区块."));
        smoothSpawnRampUp = config.getBoolean(getBasePath() + ".smooth-spawn-ramp-up", smoothSpawnRampUp,
            config.pickStringRegionBased(
                "Gradually ramp up chunk loading, ticking, and sending radius on player join, respawn, or long teleport to avoid MSPT spikes.",
                "在玩家加入、重生或长距离传送时平滑扩大区块加载、激活与发送半径，防止主线程 MSPT 尖峰。"));
    }
}
