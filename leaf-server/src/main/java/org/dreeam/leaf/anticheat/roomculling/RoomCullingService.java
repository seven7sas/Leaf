package org.dreeam.leaf.anticheat.roomculling;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.dreeam.leaf.config.modules.anticheat.UndergroundRoomCullingConfig;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RoomCullingService {

    private static final int REL_RANGE = 4; // -4 to +4 sections
    private static final int GRID_DIM = 9;   // 2 * 4 + 1
    private static final int TOTAL_SECTIONS = 9 * 9 * 9; // 729
    private static final int TOTAL_WORDS = TOTAL_SECTIONS * 64; // 46656

    private static final ThreadLocal<BFSCache> BFS_CACHE = ThreadLocal.withInitial(BFSCache::new);

    private static final class BFSCache {
        final long[] visited = new long[TOTAL_WORDS];
        final long[] frontier = new long[TOTAL_WORDS];
        final long[] nextFrontier = new long[TOTAL_WORDS];
        final int[] activeSections = new int[TOTAL_SECTIONS];
        final int[] nextActiveSections = new int[TOTAL_SECTIONS];
        final boolean[] inNextActive = new boolean[TOTAL_SECTIONS];
        final int[] touchedSections = new int[TOTAL_SECTIONS];
        final boolean[] hasTouched = new boolean[TOTAL_SECTIONS];
        int nextActiveCount;
        int touchedCount;
        LevelChunk cachedChunk;
        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;
        final LongOpenHashSet newlyRevealedSections = new LongOpenHashSet();
        final LongOpenHashSet chunksToRefresh = new LongOpenHashSet();
    }

    private RoomCullingService() {
    }

    private static volatile long[] PERMEABLE_BITS;

    private static long[] getOrInitPermeableBits() {
        long[] bits = PERMEABLE_BITS;
        if (bits != null) {
            return bits;
        }
        synchronized (RoomCullingService.class) {
            if (PERMEABLE_BITS != null) {
                return PERMEABLE_BITS;
            }
            int maxId = 0;
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                int id = Block.getId(state);
                if (id > maxId) {
                    maxId = id;
                }
            }
            long[] table = new long[(maxId + 64) >>> 6];
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                int id = Block.getId(state);
                if (id >= 0 && isPermeableSlow(state)) {
                    table[id >>> 6] |= (1L << id);
                }
            }
            PERMEABLE_BITS = table;
            return table;
        }
    }

    public static boolean isPermeable(BlockState state) {
        if (state == null) {
            return true;
        }
        int id = Block.getId(state);
        if (id < 0) {
            return isPermeableSlow(state);
        }
        long[] bits = getOrInitPermeableBits();
        int word = id >>> 6;
        if (word < bits.length) {
            return ((bits[word] >>> id) & 1L) != 0L;
        }
        return isPermeableSlow(state);
    }

    public static boolean isPermeableSlow(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        Block block = state.getBlock();
        if (block instanceof TransparentBlock || block instanceof IronBarsBlock || block instanceof BarrierBlock) {
            return true;
        }
        if (block instanceof DoorBlock) {
            return state.getValue(DoorBlock.OPEN);
        }
        if (block instanceof TrapDoorBlock) {
            return state.getValue(TrapDoorBlock.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return state.getValue(FenceGateBlock.OPEN);
        }
        if (!state.canOcclude()) {
            return !(block instanceof SlabBlock || block instanceof StairBlock ||
                     block instanceof FenceBlock || block instanceof WallBlock);
        }
        return false;
    }

    private static final int[] WATER_SAMPLE_POINTS = {1, 5, 10, 14};

    public static boolean isSectionInWaterBody(int sectionY, int worldSurface, int oceanFloor) {
        if (worldSurface <= oceanFloor) {
            return false;
        }
        int sectionMinY = sectionY << 4;
        int sectionMaxY = sectionMinY + 15;
        return sectionMaxY >= oceanFloor && sectionMinY < worldSurface;
    }

    public static boolean isSectionInWaterBody(@Nullable LevelChunk chunk, int sectionY) {
        if (chunk == null) {
            return false;
        }
        for (int x : WATER_SAMPLE_POINTS) {
            for (int z : WATER_SAMPLE_POINTS) {
                int worldSurface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int oceanFloor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                if (isSectionInWaterBody(sectionY, worldSurface, oceanFloor)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isZeroSkylight(LevelLightEngine lightEngine, int chunkX, int sectionY, int chunkZ) {
        if (lightEngine == null) {
            return false;
        }
        var starlight = lightEngine.starlight$getLightEngine();
        if (starlight != null) {
            return starlight.isSkyLightZero(chunkX, sectionY, chunkZ);
        }
        LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
        if (skyListener == null) {
            return false;
        }
        DataLayer dataLayer = skyListener.getDataLayerData(SectionPos.of(chunkX, sectionY, chunkZ));
        if (dataLayer == null) {
            return false;
        }
        if (dataLayer.isEmpty()) {
            return true;
        }
        if (dataLayer.isDefinitelyHomogenous()) {
            return dataLayer.isDefinitelyFilledWith(0);
        }
        byte[] raw = dataLayer.getData();
        if (raw == null) {
            return dataLayer.isDefinitelyFilledWith(0);
        }
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean isZeroSkylight(LevelLightEngine lightEngine, SectionPos sectionPos) {
        return isZeroSkylight(lightEngine, sectionPos.getX(), sectionPos.getY(), sectionPos.getZ());
    }

    public static boolean isSectionCulledFast(PlayerRoomVisibilityTracker tracker, ServerLevel level, @Nullable LevelChunk chunk, int chunkX, int sectionY, int chunkZ, @Nullable LevelChunkSection section) {
        if (section == null && level != null) {
            if (chunk == null) {
                chunk = level.getChunkIfLoaded(chunkX, chunkZ);
            }
            if (chunk != null) {
                int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
                if (sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount()) {
                    section = chunk.getSections()[sectionIndex];
                }
            }
        }
        if (section == null || section.hasOnlyAir()) {
            return false;
        }
        int topY = (sectionY << 4) + 15;
        if (topY > UndergroundRoomCullingConfig.maxY) {
            return false;
        }
        if (chunk == null && level != null) {
            chunk = level.getChunkIfLoaded(chunkX, chunkZ);
        }
        if (isSectionInWaterBody(chunk, sectionY)) {
            return false;
        }
        if (UndergroundRoomCullingConfig.requireZeroSkylight && level != null && !isZeroSkylight(level.getLightEngine(), chunkX, sectionY, chunkZ)) {
            return false;
        }
        long sectionKey = SectionPos.asLong(chunkX, sectionY, chunkZ);
        return !tracker.isRevealed(sectionKey);
    }

    public static boolean isSectionCulledFast(PlayerRoomVisibilityTracker tracker, ServerLevel level, int chunkX, int sectionY, int chunkZ, @Nullable LevelChunkSection section) {
        return isSectionCulledFast(tracker, level, null, chunkX, sectionY, chunkZ, section);
    }

    public static boolean isSectionCulledFast(PlayerRoomVisibilityTracker tracker, ServerLevel level, int chunkX, int sectionY, int chunkZ) {
        return isSectionCulledFast(tracker, level, null, chunkX, sectionY, chunkZ, null);
    }

    public static boolean isSectionCulled(ServerPlayer player, ServerLevel level, int chunkX, int sectionY, int chunkZ) {
        return isSectionCulled(player, level, chunkX, sectionY, chunkZ, null);
    }

    public static boolean isSectionCulled(ServerPlayer player, ServerLevel level, int chunkX, int sectionY, int chunkZ, @Nullable LevelChunkSection section) {
        if (!UndergroundRoomCullingConfig.isWorldEnabled(level.getWorld().getName())) {
            return false;
        }
        if (player.leaf$roomVisibility.canBypass(player)) {
            return false;
        }
        return isSectionCulledFast(player.leaf$roomVisibility, level, null, chunkX, sectionY, chunkZ, section);
    }

    public static void updateVisibility(ServerPlayer player, int eyeX, int eyeY, int eyeZ) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        PlayerRoomVisibilityTracker tracker = player.leaf$roomVisibility;
        BFSCache cache = BFS_CACHE.get();
        cache.newlyRevealedSections.clear();
        cache.chunksToRefresh.clear();

        // 1. Direct sight radius pre-reveal
        int directRadius = UndergroundRoomCullingConfig.directSightRadius;
        int minSecX = (eyeX - directRadius) >> 4;
        int maxSecX = (eyeX + directRadius) >> 4;
        int minSecY = (eyeY - directRadius) >> 4;
        int maxSecY = (eyeY + directRadius) >> 4;
        int minSecZ = (eyeZ - directRadius) >> 4;
        int maxSecZ = (eyeZ + directRadius) >> 4;

        for (int sx = minSecX; sx <= maxSecX; sx++) {
            for (int sy = minSecY; sy <= maxSecY; sy++) {
                for (int sz = minSecZ; sz <= maxSecZ; sz++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    long chunkPosLong = ChunkPos.asLong(sx, sz);
                    int secIdx = sy - serverLevel.getMinSectionY();
                    long secBit = (secIdx >= 0 && secIdx < 64) ? (1L << secIdx) : 0L;
                    if (tracker.revealSectionAndShouldRefresh(key, chunkPosLong, secBit)) {
                        cache.newlyRevealedSections.add(key);
                        cache.chunksToRefresh.add(chunkPosLong);
                    }
                }
            }
        }

        // 2. Bitwise 3D-BFS Traversal - only if player is in a dark/underground space (playerSkyLight == 0)
        int playerSkyLight = serverLevel.getBrightness(LightLayer.SKY, net.minecraft.core.BlockPos.containing(eyeX, eyeY, eyeZ));
        if (playerSkyLight == 0) {
            runBitwiseWaveBFS(serverLevel, tracker, cache, eyeX, eyeY, eyeZ);
        }

        // Apply newly revealed sections and refresh chunks
        if (!cache.chunksToRefresh.isEmpty()) {
            for (long chunkPosLong : cache.chunksToRefresh) {
                int cx = ChunkPos.getX(chunkPosLong);
                int cz = ChunkPos.getZ(chunkPosLong);
                LevelChunk chunk = serverLevel.getChunkIfLoaded(cx, cz);
                if (chunk != null) {
                    refreshChunk(player, serverLevel, chunk);
                }
            }
        }

        // 3. Directional View Frustum Raycasting
        castViewFrustumRays(player, serverLevel, tracker);
    }

    private static void addWave(BFSCache cache, ServerLevel serverLevel,
                                int targetSIdx, int targetW, long bits,
                                int targetSecX, int targetSecY, int targetSecZ) {
        if (targetSIdx < 0 || targetSIdx >= TOTAL_SECTIONS || bits == 0L) {
            return;
        }
        if (cache.cachedChunk == null || cache.cachedChunkX != targetSecX || cache.cachedChunkZ != targetSecZ) {
            cache.cachedChunk = serverLevel.getChunkIfLoaded(targetSecX, targetSecZ);
            cache.cachedChunkX = targetSecX;
            cache.cachedChunkZ = targetSecZ;
        }
        LevelChunk chunk = cache.cachedChunk;
        if (chunk == null) {
            return;
        }
        int secIdx = chunk.getSectionIndexFromSectionY(targetSecY);
        if (secIdx < 0 || secIdx >= chunk.getSectionsCount()) {
            return;
        }
        LevelChunkSection sec = chunk.getSections()[secIdx];
        long[] mask = sec != null ? sec.leaf$getOcclusionMask() : null;
        if (mask == LevelChunkSection.LEAF_ALL_SOLID_MASK) {
            return; // Solid rock section - wave stops!
        }
        long permeable = (mask != null) ? ~mask[targetW] : -1L;
        int targetBase = targetSIdx * 64;
        long newBits = bits & permeable & ~cache.visited[targetBase + targetW];
        if (newBits != 0L) {
            cache.visited[targetBase + targetW] |= newBits;
            cache.nextFrontier[targetBase + targetW] |= newBits;
            if (!cache.inNextActive[targetSIdx]) {
                cache.inNextActive[targetSIdx] = true;
                cache.nextActiveSections[cache.nextActiveCount++] = targetSIdx;
            }
            if (!cache.hasTouched[targetSIdx]) {
                cache.hasTouched[targetSIdx] = true;
                cache.touchedSections[cache.touchedCount++] = targetSIdx;
            }
        }
    }

    private static void runBitwiseWaveBFS(ServerLevel serverLevel,
                                          PlayerRoomVisibilityTracker tracker, BFSCache cache,
                                          int eyeX, int eyeY, int eyeZ) {
        int startSecX = eyeX >> 4;
        int startSecY = eyeY >> 4;
        int startSecZ = eyeZ >> 4;

        int lx = eyeX & 15;
        int ly = eyeY & 15;
        int lz = eyeZ & 15;
        int startBit = (ly << 8) | (lz << 4) | lx;
        int startWord = startBit >>> 6;
        long startBitMask = 1L << (startBit & 63);

        int centerSIdx = 4 + 4 * 9 + 4 * 81; // 364 (relX=0, relY=0, relZ=0)

        cache.cachedChunk = serverLevel.getChunkIfLoaded(startSecX, startSecZ);
        cache.cachedChunkX = startSecX;
        cache.cachedChunkZ = startSecZ;
        if (cache.cachedChunk == null) {
            return;
        }
        int startSecIdx = cache.cachedChunk.getSectionIndexFromSectionY(startSecY);
        LevelChunkSection startSec = (startSecIdx >= 0 && startSecIdx < cache.cachedChunk.getSectionsCount()) ? cache.cachedChunk.getSections()[startSecIdx] : null;
        long[] startMask = startSec != null ? startSec.leaf$getOcclusionMask() : null;

        if (startMask == LevelChunkSection.LEAF_ALL_SOLID_MASK) {
            return; // Starting inside solid rock
        }
        if (startMask != null && ((startMask[startWord] & startBitMask) != 0L)) {
            return; // Starting block is solid
        }

        int activeCount = 0;
        cache.nextActiveCount = 0;
        cache.touchedCount = 0;

        int centerBase = centerSIdx * 64;
        cache.frontier[centerBase + startWord] = startBitMask;
        cache.visited[centerBase + startWord] = startBitMask;
        cache.activeSections[activeCount++] = centerSIdx;
        cache.touchedSections[cache.touchedCount++] = centerSIdx;
        cache.hasTouched[centerSIdx] = true;

        try {
            while (activeCount > 0) {
                for (int a = 0; a < activeCount; a++) {
                    int sIdx = cache.activeSections[a];
                    int relX = (sIdx % 9) - 4;
                    int relY = ((sIdx / 9) % 9) - 4;
                    int relZ = (sIdx / 81) - 4;

                    int secX = startSecX + relX;
                    int secY = startSecY + relY;
                    int secZ = startSecZ + relZ;

                    long secKey = SectionPos.asLong(secX, secY, secZ);
                    long chunkPosLong = ChunkPos.asLong(secX, secZ);
                    int secIdx = secY - serverLevel.getMinSectionY();
                    long secBit = (secIdx >= 0 && secIdx < 64) ? (1L << secIdx) : 0L;
                    if (tracker.revealSectionAndShouldRefresh(secKey, chunkPosLong, secBit)) {
                        cache.newlyRevealedSections.add(secKey);
                        cache.chunksToRefresh.add(chunkPosLong);
                    }

                    int base = sIdx * 64;
                    for (int w = 0; w < 64; w++) {
                        long f = cache.frontier[base + w];
                        if (f == 0L) {
                            continue;
                        }
                        cache.frontier[base + w] = 0L;

                        // 1. East (+X)
                        long east = (f << 1) & 0xFFFEFFFEFFFEFFFEL;
                        if (east != 0L) {
                            addWave(cache, serverLevel, sIdx, w, east, secX, secY, secZ);
                        }
                        long leakE = (f & 0x8000800080008000L) >>> 15;
                        if (leakE != 0L && relX < 4 && (relX + 1) * (relX + 1) + relY * relY + relZ * relZ <= 16) {
                            addWave(cache, serverLevel, sIdx + 1, w, leakE, secX + 1, secY, secZ);
                        }

                        // 2. West (-X)
                        long west = (f >>> 1) & 0x7FFF7FFF7FFF7FFFL;
                        if (west != 0L) {
                            addWave(cache, serverLevel, sIdx, w, west, secX, secY, secZ);
                        }
                        long leakW = (f & 0x0001000100010001L) << 15;
                        if (leakW != 0L && relX > -4 && (relX - 1) * (relX - 1) + relY * relY + relZ * relZ <= 16) {
                            addWave(cache, serverLevel, sIdx - 1, w, leakW, secX - 1, secY, secZ);
                        }

                        // 3. South (+Z)
                        long south = f << 16;
                        if (south != 0L) {
                            addWave(cache, serverLevel, sIdx, w, south, secX, secY, secZ);
                        }
                        long leakS = f >>> 48;
                        if (leakS != 0L) {
                            if ((w & 3) < 3) {
                                addWave(cache, serverLevel, sIdx, w + 1, leakS, secX, secY, secZ);
                            } else if (relZ < 4 && relX * relX + relY * relY + (relZ + 1) * (relZ + 1) <= 16) {
                                addWave(cache, serverLevel, sIdx + 81, w - 3, leakS, secX, secY, secZ + 1);
                            }
                        }

                        // 4. North (-Z)
                        long north = f >>> 16;
                        if (north != 0L) {
                            addWave(cache, serverLevel, sIdx, w, north, secX, secY, secZ);
                        }
                        long leakN = (f & 0xFFFFL) << 48;
                        if (leakN != 0L) {
                            if ((w & 3) > 0) {
                                addWave(cache, serverLevel, sIdx, w - 1, leakN, secX, secY, secZ);
                            } else if (relZ > -4 && relX * relX + relY * relY + (relZ - 1) * (relZ - 1) <= 16) {
                                addWave(cache, serverLevel, sIdx - 81, w + 3, leakN, secX, secY, secZ - 1);
                            }
                        }

                        // 5. Up (+Y)
                        if (w < 60) {
                            addWave(cache, serverLevel, sIdx, w + 4, f, secX, secY, secZ);
                        } else if (relY < 4 && relX * relX + (relY + 1) * (relY + 1) + relZ * relZ <= 16) {
                            addWave(cache, serverLevel, sIdx + 9, w - 60, f, secX, secY + 1, secZ);
                        }

                        // 6. Down (-Y)
                        if (w >= 4) {
                            addWave(cache, serverLevel, sIdx, w - 4, f, secX, secY, secZ);
                        } else if (relY > -4 && relX * relX + (relY - 1) * (relY - 1) + relZ * relZ <= 16) {
                            addWave(cache, serverLevel, sIdx - 9, w + 60, f, secX, secY - 1, secZ);
                        }
                    }
                }

                activeCount = cache.nextActiveCount;
                cache.nextActiveCount = 0;
                for (int i = 0; i < activeCount; i++) {
                    int s = cache.nextActiveSections[i];
                    cache.activeSections[i] = s;
                    cache.inNextActive[s] = false;
                    int base = s * 64;
                    System.arraycopy(cache.nextFrontier, base, cache.frontier, base, 64);
                    java.util.Arrays.fill(cache.nextFrontier, base, base + 64, 0L);
                }
            }
        } finally {
            for (int i = 0; i < cache.touchedCount; i++) {
                int s = cache.touchedSections[i];
                int base = s * 64;
                java.util.Arrays.fill(cache.visited, base, base + 64, 0L);
                java.util.Arrays.fill(cache.frontier, base, base + 64, 0L);
                java.util.Arrays.fill(cache.nextFrontier, base, base + 64, 0L);
                cache.hasTouched[s] = false;
                cache.inNextActive[s] = false;
            }
        }
    }

    @FunctionalInterface
    public interface BlockGetterFunction {
        BlockState getBlockState(int x, int y, int z);
    }

    public static int castSingleRayDDA(ServerLevel level,
                                       PlayerRoomVisibilityTracker tracker,
                                       double startX, double startY, double startZ,
                                       double dirX, double dirY, double dirZ,
                                       int maxDist, int maxY, int minY,
                                       LongOpenHashSet newlyRevealedSections,
                                       LongOpenHashSet chunksToRefresh) {
        int bx = Mth.floor(startX);
        int by = Mth.floor(startY);
        int bz = Mth.floor(startZ);

        int stepX = dirX > 0 ? 1 : (dirX < 0 ? -1 : 0);
        int stepY = dirY > 0 ? 1 : (dirY < 0 ? -1 : 0);
        int stepZ = dirZ > 0 ? 1 : (dirZ < 0 ? -1 : 0);

        double tDeltaX = dirX != 0 ? Math.abs(1.0 / dirX) : Double.MAX_VALUE;
        double tDeltaY = dirY != 0 ? Math.abs(1.0 / dirY) : Double.MAX_VALUE;
        double tDeltaZ = dirZ != 0 ? Math.abs(1.0 / dirZ) : Double.MAX_VALUE;

        double tMaxX = dirX > 0 ? (bx + 1.0 - startX) * tDeltaX : (dirX < 0 ? (startX - bx) * tDeltaX : Double.MAX_VALUE);
        double tMaxY = dirY > 0 ? (by + 1.0 - startY) * tDeltaY : (dirY < 0 ? (startY - by) * tDeltaY : Double.MAX_VALUE);
        double tMaxZ = dirZ > 0 ? (bz + 1.0 - startZ) * tDeltaZ : (dirZ < 0 ? (startZ - bz) * tDeltaZ : Double.MAX_VALUE);

        double distTraveled = 0.0;
        int blocksTraversed = 0;

        LevelChunk cachedChunk = null;
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        LevelChunkSection cachedSection = null;
        int lastSectionIndex = Integer.MIN_VALUE;

        while (distTraveled < maxDist) {
            if (by > maxY || by < minY) {
                break;
            }

            blocksTraversed++;
            int secX = bx >> 4;
            int secY = by >> 4;
            int secZ = bz >> 4;
            long secKey = SectionPos.asLong(secX, secY, secZ);
            long chunkPosLong = ChunkPos.asLong(secX, secZ);

            if (tracker != null) {
                int secIdx = secY - level.getMinSectionY();
                long secBit = (secIdx >= 0 && secIdx < 64) ? (1L << secIdx) : 0L;
                if (tracker.revealSectionAndShouldRefresh(secKey, chunkPosLong, secBit)) {
                    if (newlyRevealedSections != null) newlyRevealedSections.add(secKey);
                    if (chunksToRefresh != null) chunksToRefresh.add(chunkPosLong);
                }
            }

            // Chunk and section lookup with caching
            if (secX != lastChunkX || secZ != lastChunkZ) {
                cachedChunk = level.getChunkIfLoaded(secX, secZ);
                lastChunkX = secX;
                lastChunkZ = secZ;
                cachedSection = null;
                lastSectionIndex = Integer.MIN_VALUE;
            }

            if (cachedChunk == null) {
                break;
            }

            int secIdx = cachedChunk.getSectionIndex(by);
            if (secIdx != lastSectionIndex) {
                cachedSection = (secIdx >= 0 && secIdx < cachedChunk.getSectionsCount()) ? cachedChunk.getSections()[secIdx] : null;
                lastSectionIndex = secIdx;
            }

            if (cachedSection != null) {
                long[] mask = cachedSection.leaf$getOcclusionMask();
                if (mask == LevelChunkSection.LEAF_ALL_SOLID_MASK) {
                    // Hit 100% solid section - stop ray immediately
                    break;
                }
                if (mask != null) {
                    int bitIdx = ((by & 15) << 8) | ((bz & 15) << 4) | (bx & 15);
                    if (((mask[bitIdx >>> 6] >>> bitIdx) & 1L) != 0L) {
                        // Hit solid wall or obstacle - stop ray
                        break;
                    }
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    bx += stepX;
                    distTraveled = tMaxX;
                    tMaxX += tDeltaX;
                } else {
                    bz += stepZ;
                    distTraveled = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    by += stepY;
                    distTraveled = tMaxY;
                    tMaxY += tDeltaY;
                } else {
                    bz += stepZ;
                    distTraveled = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }
        return blocksTraversed;
    }

    public static int castSingleRayDDA(BlockGetterFunction blockGetter,
                                      PlayerRoomVisibilityTracker tracker,
                                      double startX, double startY, double startZ,
                                      double dirX, double dirY, double dirZ,
                                      int maxDist, int maxY, int minY,
                                      LongOpenHashSet newlyRevealedSections,
                                      LongOpenHashSet chunksToRefresh) {
        int bx = Mth.floor(startX);
        int by = Mth.floor(startY);
        int bz = Mth.floor(startZ);

        int stepX = dirX > 0 ? 1 : (dirX < 0 ? -1 : 0);
        int stepY = dirY > 0 ? 1 : (dirY < 0 ? -1 : 0);
        int stepZ = dirZ > 0 ? 1 : (dirZ < 0 ? -1 : 0);

        double tDeltaX = dirX != 0 ? Math.abs(1.0 / dirX) : Double.MAX_VALUE;
        double tDeltaY = dirY != 0 ? Math.abs(1.0 / dirY) : Double.MAX_VALUE;
        double tDeltaZ = dirZ != 0 ? Math.abs(1.0 / dirZ) : Double.MAX_VALUE;

        double tMaxX = dirX > 0 ? (bx + 1.0 - startX) * tDeltaX : (dirX < 0 ? (startX - bx) * tDeltaX : Double.MAX_VALUE);
        double tMaxY = dirY > 0 ? (by + 1.0 - startY) * tDeltaY : (dirY < 0 ? (startY - by) * tDeltaY : Double.MAX_VALUE);
        double tMaxZ = dirZ > 0 ? (bz + 1.0 - startZ) * tDeltaZ : (dirZ < 0 ? (startZ - bz) * tDeltaZ : Double.MAX_VALUE);

        double distTraveled = 0.0;
        int blocksTraversed = 0;

        while (distTraveled < maxDist) {
            if (by > maxY || by < minY) {
                break;
            }

            blocksTraversed++;
            int secX = bx >> 4;
            int secY = by >> 4;
            int secZ = bz >> 4;
            long secKey = SectionPos.asLong(secX, secY, secZ);
            long chunkPosLong = ChunkPos.asLong(secX, secZ);
            if (tracker != null) {
                int secIdx = secY - (minY >> 4);
                long secBit = (secIdx >= 0 && secIdx < 64) ? (1L << secIdx) : 0L;
                if (tracker.revealSectionAndShouldRefresh(secKey, chunkPosLong, secBit)) {
                    if (newlyRevealedSections != null) newlyRevealedSections.add(secKey);
                    if (chunksToRefresh != null) chunksToRefresh.add(chunkPosLong);
                }
            } else {
                if (newlyRevealedSections != null) newlyRevealedSections.add(secKey);
                if (chunksToRefresh != null) chunksToRefresh.add(chunkPosLong);
            }

            BlockState state = blockGetter.getBlockState(bx, by, bz);
            if (state != null && !isPermeable(state)) {
                // Hit solid wall or obstacle - stop ray
                break;
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    bx += stepX;
                    distTraveled = tMaxX;
                    tMaxX += tDeltaX;
                } else {
                    bz += stepZ;
                    distTraveled = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    by += stepY;
                    distTraveled = tMaxY;
                    tMaxY += tDeltaY;
                } else {
                    bz += stepZ;
                    distTraveled = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }
        return blocksTraversed;
    }

    public static void castViewFrustumRays(ServerPlayer player, ServerLevel level, PlayerRoomVisibilityTracker tracker,
                                           double eyeX, double eyeY, double eyeZ,
                                           float baseYaw, float basePitch,
                                           BFSCache cache) {
        if (!UndergroundRoomCullingConfig.raycastEnabled || eyeY > UndergroundRoomCullingConfig.maxY + 32) {
            return;
        }

        int maxDist = Math.max(16, UndergroundRoomCullingConfig.raycastMaxDistance);
        int maxY = UndergroundRoomCullingConfig.maxY + 16;
        int minY = level.getMinY();

        int horizSteps = 9;
        int vertSteps = 7;
        float fovH = UndergroundRoomCullingConfig.raycastFovDegrees;
        float halfFovH = fovH * 0.5f;
        float fovV = 75.0f;
        float halfFovV = fovV * 0.5f;

        for (int hi = 0; hi < horizSteps; hi++) {
            float yawOffset = -halfFovH + (hi * fovH / (horizSteps - 1));
            float rayYaw = baseYaw + yawOffset;

            for (int vi = 0; vi < vertSteps; vi++) {
                float pitchOffset = -halfFovV + (vi * fovV / (vertSteps - 1));
                float rayPitch = Mth.clamp(basePitch + pitchOffset, -89.9f, 89.9f);

                float radPitch = rayPitch * (float) (Math.PI / 180.0);
                float radYaw = -rayYaw * (float) (Math.PI / 180.0);
                double cosPitch = Math.cos(radPitch);
                double dirX = Math.sin(radYaw) * cosPitch;
                double dirY = -Math.sin(radPitch);
                double dirZ = Math.cos(radYaw) * cosPitch;

                castSingleRayDDA(level, tracker, eyeX, eyeY, eyeZ, dirX, dirY, dirZ, maxDist, maxY, minY, cache.newlyRevealedSections, cache.chunksToRefresh);
            }
        }
    }

    public static void castViewFrustumRays(ServerPlayer player, ServerLevel level, PlayerRoomVisibilityTracker tracker) {
        if (!UndergroundRoomCullingConfig.raycastEnabled || player.getY() > UndergroundRoomCullingConfig.maxY + 32) {
            return;
        }
        BFSCache cache = BFS_CACHE.get();
        cache.newlyRevealedSections.clear();
        cache.chunksToRefresh.clear();

        castViewFrustumRays(player, level, tracker, player.getX(), player.getEyeY(), player.getZ(), player.getYRot(), player.getXRot(), cache);

        if (!cache.chunksToRefresh.isEmpty()) {
            for (long chunkPosLong : cache.chunksToRefresh) {
                int cx = ChunkPos.getX(chunkPosLong);
                int cz = ChunkPos.getZ(chunkPosLong);
                LevelChunk chunk = level.getChunkIfLoaded(cx, cz);
                if (chunk != null) {
                    refreshChunk(player, level, chunk);
                }
            }
        }
    }

    public static void updateVisibilityAsync(ServerPlayer player, ServerLevel serverLevel,
                                             PlayerRoomVisibilityTracker tracker, int generation,
                                             double eyeX, double eyeY, double eyeZ,
                                             float yaw, float pitch, int skyLight) {
        if (tracker.getResetGeneration() != generation || player.hasDisconnected()) {
            return;
        }

        BFSCache cache = BFS_CACHE.get();
        cache.newlyRevealedSections.clear();
        cache.chunksToRefresh.clear();

        int ix = Mth.floor(eyeX);
        int iy = Mth.floor(eyeY);
        int iz = Mth.floor(eyeZ);

        // 1. Direct sight radius pre-reveal
        int directRadius = UndergroundRoomCullingConfig.directSightRadius;
        int minSecX = (ix - directRadius) >> 4;
        int maxSecX = (ix + directRadius) >> 4;
        int minSecY = (iy - directRadius) >> 4;
        int maxSecY = (iy + directRadius) >> 4;
        int minSecZ = (iz - directRadius) >> 4;
        int maxSecZ = (iz + directRadius) >> 4;

        for (int sx = minSecX; sx <= maxSecX; sx++) {
            for (int sy = minSecY; sy <= maxSecY; sy++) {
                for (int sz = minSecZ; sz <= maxSecZ; sz++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    long chunkPosLong = ChunkPos.asLong(sx, sz);
                    int secIdx = sy - serverLevel.getMinSectionY();
                    long secBit = (secIdx >= 0 && secIdx < 64) ? (1L << secIdx) : 0L;
                    if (tracker.revealSectionAndShouldRefresh(key, chunkPosLong, secBit)) {
                        cache.newlyRevealedSections.add(key);
                        cache.chunksToRefresh.add(chunkPosLong);
                    }
                }
            }
        }

        if (tracker.getResetGeneration() != generation || player.hasDisconnected()) {
            return;
        }

        // 2. Bitwise 3D-BFS Traversal - only if in dark space (skyLight == 0)
        if (skyLight == 0) {
            runBitwiseWaveBFS(serverLevel, tracker, cache, ix, iy, iz);
        }

        if (tracker.getResetGeneration() != generation || player.hasDisconnected()) {
            return;
        }

        // 3. Directional View Frustum Raycasting
        if (UndergroundRoomCullingConfig.raycastEnabled && eyeY <= UndergroundRoomCullingConfig.maxY + 32) {
            castViewFrustumRays(player, serverLevel, tracker, eyeX, eyeY, eyeZ, yaw, pitch, cache);
        }

        if (tracker.getResetGeneration() != generation || player.hasDisconnected()) {
            return;
        }

        // Enqueue culled chunks for throttled refresh on server tick
        if (!cache.chunksToRefresh.isEmpty()) {
            for (long chunkPosLong : cache.chunksToRefresh) {
                tracker.enqueueChunkRefresh(chunkPosLong);
            }
        }
    }

    private static final java.util.BitSet EMPTY_LIGHT_BITSET = new java.util.BitSet();

    public static void refreshChunk(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        if (player.connection == null || player.hasDisconnected()) {
            return;
        }
        final boolean shouldModify = level.chunkPacketBlockController.shouldModify(player, chunk);
        final net.minecraft.world.level.block.entity.BlockEntity[] blockEntities =
            chunk.blockEntities.values().toArray(new net.minecraft.world.level.block.entity.BlockEntity[0]);
        final java.util.Map<Heightmap.Types, long[]> heightmaps = new java.util.EnumMap<>(Heightmap.Types.class);
        for (java.util.Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (entry.getKey().sendToClient()) {
                heightmaps.put(entry.getKey(), entry.getValue().getRawData());
            }
        }

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            if (player.connection == null || player.hasDisconnected() || player.level() != level) {
                return;
            }
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                chunk, level.getLightEngine(), EMPTY_LIGHT_BITSET, EMPTY_LIGHT_BITSET, shouldModify, blockEntities, heightmaps, player
            );
            player.connection.send(packet);
        }, Util.backgroundExecutor());
    }

    public static void refreshChunk(ServerPlayer player, ServerLevel level, long chunkPosLong) {
        if (player.connection == null || player.hasDisconnected()) {
            return;
        }
        int cx = ChunkPos.getX(chunkPosLong);
        int cz = ChunkPos.getZ(chunkPosLong);
        LevelChunk chunk = level.getChunkIfLoaded(cx, cz);
        if (chunk != null) {
            refreshChunk(player, level, chunk);
        }
    }

    public static void onBlockChanged(Level level, int x, int y, int z, BlockState oldState, BlockState newState) {
        if (y > UndergroundRoomCullingConfig.maxY) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!UndergroundRoomCullingConfig.isWorldEnabled(serverLevel.getWorld().getName())) {
            return;
        }
        boolean oldPermeable = isPermeable(oldState);
        boolean newPermeable = isPermeable(newState);
        if (oldPermeable == newPermeable) {
            return;
        }

        int maxDist = UndergroundRoomCullingConfig.bfsMaxDistance;
        int maxDistSq = maxDist * maxDist;

        List<ServerPlayer> players = serverLevel.players();
        for (int i = 0, len = players.size(); i < len; i++) {
            ServerPlayer player = players.get(i);
            if (player.leaf$roomVisibility.canBypass(player)) {
                continue;
            }
            int eyeX = Mth.floor(player.getX());
            int eyeY = Mth.floor(player.getEyeY());
            int eyeZ = Mth.floor(player.getZ());
            int dx = eyeX - x;
            int dy = eyeY - y;
            int dz = eyeZ - z;
            if (dx * dx + dy * dy + dz * dz <= maxDistSq) {
                player.leaf$roomVisibility.markPendingBlockRecalc();
            }
        }
    }
}
