package org.dreeam.leaf.anticheat.roomculling;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.Util;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.entity.Player;
import org.dreeam.leaf.config.modules.anticheat.UndergroundRoomCullingConfig;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

public class PlayerRoomVisibilityTracker {

    private final StampedLock lock = new StampedLock();
    private final LongOpenHashSet revealedSections = new LongOpenHashSet();
    private final Long2LongOpenHashMap culledChunkSectionMasks = new Long2LongOpenHashMap();
    private long lastEyeSectionKey = Long.MIN_VALUE;
    private long lastEyeBlockKey = Long.MIN_VALUE;
    private final long[] viewedDirectionMask = new long[4]; // 32 yaw bins * 8 pitch bins = 256 bits
    private int lastUpdateTick = -1;

    private boolean cachedBypass;
    private int lastBypassCheckTick = -100;

    private float lastRaycastYaw = Float.NaN;
    private float lastRaycastPitch = Float.NaN;
    private int lastRaycastTick = -100;

    private volatile boolean pendingBlockRecalc = false;

    private final AtomicInteger resetGeneration = new AtomicInteger(0);
    private final AtomicBoolean recalcInFlight = new AtomicBoolean(false);
    private volatile boolean recalcQueued = false;
    private volatile double queuedEyeX, queuedEyeY, queuedEyeZ;
    private volatile float queuedYaw, queuedPitch;
    private volatile int queuedSkyLight;

    private final ConcurrentLinkedQueue<Long> pendingChunkRefreshes = new ConcurrentLinkedQueue<>();
    private final LongOpenHashSet pendingChunkRefreshSet = new LongOpenHashSet();

    public int getResetGeneration() {
        return this.resetGeneration.get();
    }

    public void enqueueChunkRefresh(long chunkPosLong) {
        synchronized (this.pendingChunkRefreshSet) {
            if (this.pendingChunkRefreshSet.add(chunkPosLong)) {
                this.pendingChunkRefreshes.add(chunkPosLong);
            }
        }
    }

    public Long pollPendingChunkRefresh() {
        Long chunkPosLong = this.pendingChunkRefreshes.poll();
        if (chunkPosLong != null) {
            synchronized (this.pendingChunkRefreshSet) {
                this.pendingChunkRefreshSet.remove(chunkPosLong.longValue());
            }
        }
        return chunkPosLong;
    }

    public int getPendingChunkRefreshCount() {
        return this.pendingChunkRefreshes.size();
    }

    public boolean isRevealed(long sectionKey) {
        long stamp = this.lock.tryOptimisticRead();
        boolean res = this.revealedSections.contains(sectionKey);
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                res = this.revealedSections.contains(sectionKey);
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return res;
    }

    public void revealSection(long sectionKey) {
        long stamp = this.lock.writeLock();
        try {
            this.revealedSections.add(sectionKey);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public void revealSections(LongSet newSections) {
        long stamp = this.lock.writeLock();
        try {
            this.revealedSections.addAll(newSections);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public void setChunkCulledMask(long chunkPosLong, long sectionsMask) {
        long stamp = this.lock.writeLock();
        try {
            if (sectionsMask == 0L) {
                this.culledChunkSectionMasks.remove(chunkPosLong);
            } else {
                this.culledChunkSectionMasks.put(chunkPosLong, sectionsMask);
            }
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public long getChunkCulledMask(long chunkPosLong) {
        long stamp = this.lock.tryOptimisticRead();
        long res = this.culledChunkSectionMasks.get(chunkPosLong);
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                res = this.culledChunkSectionMasks.get(chunkPosLong);
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return res;
    }

    public void markChunkCulled(long chunkPosLong) {
        setChunkCulledMask(chunkPosLong, -1L);
    }

    public boolean wasChunkCulled(long chunkPosLong) {
        long stamp = this.lock.tryOptimisticRead();
        boolean res = this.culledChunkSectionMasks.containsKey(chunkPosLong);
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                res = this.culledChunkSectionMasks.containsKey(chunkPosLong);
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return res;
    }

    public void clearChunkCulled(long chunkPosLong) {
        long stamp = this.lock.writeLock();
        try {
            this.culledChunkSectionMasks.remove(chunkPosLong);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public int getCulledChunkCount() {
        long stamp = this.lock.tryOptimisticRead();
        int res = this.culledChunkSectionMasks.size();
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                res = this.culledChunkSectionMasks.size();
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return res;
    }

    /**
     * Раскрывает секцию и возвращает true ТОЛЬКО если эта конкретная секция была скрыта в отправленном клиенту чанке.
     */
    public boolean revealSectionAndShouldRefresh(long sectionKey, long chunkPosLong, long sectionBit) {
        long stamp = this.lock.writeLock();
        try {
            boolean newlyRevealed = this.revealedSections.add(sectionKey);
            if (!newlyRevealed || sectionBit == 0L) {
                return false;
            }
            long culledMask = this.culledChunkSectionMasks.get(chunkPosLong);
            if ((culledMask & sectionBit) == 0L) {
                return false;
            }
            long remaining = culledMask & ~sectionBit;
            if (remaining == 0L) {
                this.culledChunkSectionMasks.remove(chunkPosLong);
            } else {
                this.culledChunkSectionMasks.put(chunkPosLong, remaining);
            }
            return true;
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public void forgetChunk(int chunkX, int chunkZ, int minSectionY, int maxSectionY) {
        long chunkPosLong = ChunkPos.asLong(chunkX, chunkZ);
        synchronized (this.pendingChunkRefreshSet) {
            this.pendingChunkRefreshSet.remove(chunkPosLong);
        }
        long stamp = this.lock.writeLock();
        try {
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                this.revealedSections.remove(SectionPos.asLong(chunkX, sy, chunkZ));
            }
            this.culledChunkSectionMasks.remove(chunkPosLong);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public void reset() {
        this.resetGeneration.incrementAndGet();
        this.pendingChunkRefreshes.clear();
        synchronized (this.pendingChunkRefreshSet) {
            this.pendingChunkRefreshSet.clear();
        }
        this.recalcQueued = false;
        long stamp = this.lock.writeLock();
        try {
            this.revealedSections.clear();
            this.culledChunkSectionMasks.clear();
            this.lastEyeSectionKey = Long.MIN_VALUE;
            this.lastEyeBlockKey = Long.MIN_VALUE;
            java.util.Arrays.fill(this.viewedDirectionMask, 0L);
            this.lastUpdateTick = -1;
            this.lastBypassCheckTick = -100;
            this.cachedBypass = false;
            this.lastRaycastYaw = Float.NaN;
            this.lastRaycastPitch = Float.NaN;
            this.lastRaycastTick = -100;
            this.pendingBlockRecalc = false;
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public void markPendingBlockRecalc() {
        this.pendingBlockRecalc = true;
    }

    public boolean hasPendingBlockRecalc() {
        return this.pendingBlockRecalc;
    }

    public void clearPendingBlockRecalc() {
        this.pendingBlockRecalc = false;
    }

    public boolean canBypass(ServerPlayer player) {
        if (player.isSpectator()) {
            return true;
        }
        int currentTick = player.tickCount;
        if (currentTick - this.lastBypassCheckTick < 100 && this.lastBypassCheckTick >= 0) {
            return this.cachedBypass;
        }
        this.lastBypassCheckTick = currentTick;
        Player bukkitPlayer = player.getBukkitEntity();
        this.cachedBypass = bukkitPlayer != null && bukkitPlayer.hasPermission("leaf.anticheat.roomculling.bypass");
        return this.cachedBypass;
    }

    public void scheduleRecalc(ServerPlayer player, ServerLevel serverLevel, double eyeX, double eyeY, double eyeZ, float yaw, float pitch, int skyLight) {
        this.queuedEyeX = eyeX;
        this.queuedEyeY = eyeY;
        this.queuedEyeZ = eyeZ;
        this.queuedYaw = yaw;
        this.queuedPitch = pitch;
        this.queuedSkyLight = skyLight;

        if (this.recalcInFlight.compareAndSet(false, true)) {
            dispatchRecalc(player, serverLevel);
        } else {
            this.recalcQueued = true;
        }
    }

    public static int getDirectionBin(float yaw, float pitch) {
        float wrappedYaw = Mth.wrapDegrees(yaw) + 180.0f; // [0, 360)
        int yawBin = ((int) Math.floor(wrappedYaw * (32.0f / 360.0f))) & 31;
        int pitchBin = Mth.clamp((int) Math.floor((pitch + 90.0f) * (8.0f / 180.0f)), 0, 7);
        return (pitchBin << 5) | yawBin; // 0..255
    }

    public long[] getViewedDirectionMaskForTest() {
        return this.viewedDirectionMask;
    }

    public long getLastEyeBlockKeyForTest() {
        return this.lastEyeBlockKey;
    }

    private void dispatchRecalc(ServerPlayer player, ServerLevel serverLevel) {
        final int currentGen = this.resetGeneration.get();
        Util.backgroundExecutor().execute(() -> {
            try {
                while (true) {
                    if (this.resetGeneration.get() != currentGen || player.hasDisconnected()) {
                        return;
                    }
                    this.recalcQueued = false;
                    double ex = this.queuedEyeX;
                    double ey = this.queuedEyeY;
                    double ez = this.queuedEyeZ;
                    float y = this.queuedYaw;
                    float p = this.queuedPitch;
                    int sl = this.queuedSkyLight;

                    try {
                        RoomCullingService.updateVisibilityAsync(player, serverLevel, this, currentGen, ex, ey, ez, y, p, sl);
                    } catch (Throwable t) {
                        // Protect against runtime exceptions (e.g. palette resize during async scan)
                    }

                    if (this.resetGeneration.get() != currentGen || player.hasDisconnected()) {
                        return;
                    }

                    if (!this.recalcQueued) {
                        break;
                    }
                }
            } finally {
                this.recalcInFlight.set(false);
                if (this.recalcQueued && this.resetGeneration.get() == currentGen && !player.hasDisconnected()) {
                    if (this.recalcInFlight.compareAndSet(false, true)) {
                        dispatchRecalc(player, serverLevel);
                    }
                }
            }
        });
    }

    public void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!UndergroundRoomCullingConfig.isWorldEnabled(serverLevel.getWorld().getName()) || canBypass(player)) {
            return;
        }

        int sent = 0;
        Long chunkPosLong;
        while (sent < 2 && (chunkPosLong = pollPendingChunkRefresh()) != null) {
            RoomCullingService.refreshChunk(player, serverLevel, chunkPosLong);
            sent++;
        }

        int currentTick = player.tickCount;
        int eyeX = Mth.floor(player.getX());
        int eyeY = Mth.floor(player.getEyeY());
        int eyeZ = Mth.floor(player.getZ());
        long eyeBlockKey = net.minecraft.core.BlockPos.asLong(eyeX, eyeY, eyeZ);
        long eyeSectionKey = SectionPos.asLong(eyeX >> 4, eyeY >> 4, eyeZ >> 4);
        boolean blockRecalc = this.pendingBlockRecalc;
        boolean sectionChanged = (eyeSectionKey != this.lastEyeSectionKey);

        if (eyeBlockKey != this.lastEyeBlockKey || blockRecalc) {
            this.lastEyeBlockKey = eyeBlockKey;
            java.util.Arrays.fill(this.viewedDirectionMask, 0L);
        }

        float yaw = player.getYRot();
        float pitch = player.getXRot();

        if (sectionChanged || blockRecalc) {
            if (blockRecalc) {
                this.pendingBlockRecalc = false;
            }
            this.lastEyeSectionKey = eyeSectionKey;
            this.lastUpdateTick = currentTick;
            this.lastRaycastYaw = yaw;
            this.lastRaycastPitch = pitch;
            this.lastRaycastTick = currentTick;

            int dirBin = getDirectionBin(yaw, pitch);
            this.viewedDirectionMask[dirBin >>> 6] |= (1L << (dirBin & 63));

            int skyLight = serverLevel.getBrightness(net.minecraft.world.level.LightLayer.SKY, net.minecraft.core.BlockPos.containing(eyeX, eyeY, eyeZ));
            scheduleRecalc(player, serverLevel, player.getX(), player.getEyeY(), player.getZ(), yaw, pitch, skyLight);
            return;
        }

        // eyeSectionKey == this.lastEyeSectionKey && !blockRecalc
        if (!UndergroundRoomCullingConfig.raycastEnabled || player.getY() > UndergroundRoomCullingConfig.maxY + 32 || getCulledChunkCount() == 0) {
            return;
        }

        float yawDiff = Math.abs(Mth.wrapDegrees(yaw - this.lastRaycastYaw));
        float pitchDiff = Math.abs(pitch - this.lastRaycastPitch);
        if ((!Float.isNaN(this.lastRaycastYaw) && yawDiff < 18.0f && pitchDiff < 18.0f) || (currentTick - this.lastRaycastTick < 5)) {
            return;
        }

        int dirBin = getDirectionBin(yaw, pitch);
        int w = dirBin >>> 6;
        long bit = 1L << (dirBin & 63);
        if ((this.viewedDirectionMask[w] & bit) != 0L) {
            return;
        }
        this.viewedDirectionMask[w] |= bit;
        this.lastRaycastYaw = yaw;
        this.lastRaycastPitch = pitch;
        this.lastRaycastTick = currentTick;

        int skyLight = serverLevel.getBrightness(net.minecraft.world.level.LightLayer.SKY, net.minecraft.core.BlockPos.containing(eyeX, eyeY, eyeZ));
        scheduleRecalc(player, serverLevel, player.getX(), player.getEyeY(), player.getZ(), yaw, pitch, skyLight);
    }

    public int getRevealedCount() {
        long stamp = this.lock.tryOptimisticRead();
        int res = this.revealedSections.size();
        if (!this.lock.validate(stamp)) {
            stamp = this.lock.readLock();
            try {
                res = this.revealedSections.size();
            } finally {
                this.lock.unlockRead(stamp);
            }
        }
        return res;
    }

    public boolean getCachedBypass() {
        return this.cachedBypass;
    }

    public int getLastBypassCheckTick() {
        return this.lastBypassCheckTick;
    }

    public void setBypassCacheForTest(boolean bypass, int tick) {
        this.cachedBypass = bypass;
        this.lastBypassCheckTick = tick;
    }
}
