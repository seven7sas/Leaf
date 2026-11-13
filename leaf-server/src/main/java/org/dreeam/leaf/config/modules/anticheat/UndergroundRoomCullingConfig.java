package org.dreeam.leaf.config.modules.anticheat;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

import java.util.List;
import java.util.Set;

public class UndergroundRoomCullingConfig extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.ANTICHEAT.getBaseKeyName() + ".underground-room-culling";
    }

    public static boolean enabled = true;
    public static List<String> enabledWorlds = List.of("world");
    private static Set<String> enabledWorldsSet = new ObjectOpenHashSet<>(enabledWorlds);
    public static int maxY = 56;
    public static boolean requireZeroSkylight = true;
    public static int directSightRadius = 4;
    public static int bfsMaxDistance = 32;
    public static int bfsMaxVisitedBlocks = 65536;
    public static boolean raycastEnabled = true;
    public static int raycastMaxDistance = 112;
    public static int raycastFovDegrees = 110;

    private static boolean initialized;

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                Underground Room Culling anticheat module.
                Hides closed underground rooms, caves, and stashes behind walls from Xray, CaveFinder, and passive Freecam,
                while keeping open caves and surface canyons in direct line-of-sight fully visible.""",
            """
                地下房间剔除反作弊模块.
                向 Xray、CaveFinder 和被动 Freecam 隐藏墙后的封闭地下房间、洞穴和藏匿点,
                同时保持视线内开放的洞穴和峡谷完全可见.""");

        if (initialized) {
            config.getConfigSection(getBasePath());
            return;
        }
        initialized = true;

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled,
            config.pickStringRegionBased("Whether to enable underground room culling.", "是否启用地下房间剔除."));
        enabledWorlds = config.getList(getBasePath() + ".enabled-worlds", enabledWorlds,
            config.pickStringRegionBased("Worlds where underground room culling is enabled.", "启用地下房间剔除的世界列表."));
        enabledWorldsSet = new ObjectOpenHashSet<>(enabledWorlds);
        maxY = config.getInt(getBasePath() + ".max-y", maxY,
            config.pickStringRegionBased("Maximum Y coordinate for room culling. Sections above this will not be culled.", "房间剔除的最大 Y 坐标. 高于此高度的区块段不会被剔除."));
        requireZeroSkylight = config.getBoolean(getBasePath() + ".require-zero-skylight", requireZeroSkylight,
            config.pickStringRegionBased("Only cull sections with 0 skylight. Keeps open-air pits and ravines untouched.", "仅剔除天光值为0的区块段. 保持露天矿坑和峡谷不受影响."));
        directSightRadius = config.getInt(getBasePath() + ".direct-sight-radius", directSightRadius,
            config.pickStringRegionBased("Direct sight radius (in blocks) around player eye that is always revealed without occlusion checks.", "玩家视线周围始终直接可见的半径(格数)."));
        bfsMaxDistance = config.getInt(getBasePath() + ".bfs-max-distance", bfsMaxDistance,
            config.pickStringRegionBased("Maximum BFS distance (in blocks) to trace visible air/transparent blocks.", "追踪可见空气/透明方块的最大 BFS 距离(格数)."));
        bfsMaxVisitedBlocks = config.getInt(getBasePath() + ".bfs-max-visited-blocks", bfsMaxVisitedBlocks,
            config.pickStringRegionBased("Maximum number of blocks visited during BFS traversal.", "BFS 遍历期间访问的最大方块数."));
        raycastEnabled = config.getBoolean(getBasePath() + ".raycast-enabled", raycastEnabled,
            config.pickStringRegionBased("Whether to enable directional 3D-DDA raycasting along player camera view frustum.", "是否沿玩家摄像机视锥启用定向3D-DDA光线投射."));
        raycastMaxDistance = config.getInt(getBasePath() + ".raycast-max-distance", raycastMaxDistance,
            config.pickStringRegionBased("Maximum distance for directional 3D-DDA raycasting in blocks.", "定向3D-DDA光线投射的最大距离(格数)."));
        raycastFovDegrees = config.getInt(getBasePath() + ".raycast-fov-degrees", raycastFovDegrees,
            config.pickStringRegionBased("Horizontal field of view angle in degrees for raycast fan.", "光线投射扇形的水平视场角(度)."));
    }

    public static boolean isWorldEnabled(String worldName) {
        return enabled && enabledWorldsSet.contains(worldName);
    }
}
