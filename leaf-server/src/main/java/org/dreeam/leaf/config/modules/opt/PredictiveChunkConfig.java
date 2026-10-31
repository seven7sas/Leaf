package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class PredictiveChunkConfig extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".chunk-loading";
    }

    public static boolean predictiveVelocityConeSend = true;

    @Override
    public void onLoaded() {
        predictiveVelocityConeSend = config.getBoolean(getBasePath() + ".predictive-velocity-cone-send", predictiveVelocityConeSend,
            config.pickStringRegionBased(
                "Prioritize sending chunks in the direction the player is moving (velocity cone).",
                "是否根据玩家移动速度向量（速度锥）优先发送前方的区块."));
    }
}
