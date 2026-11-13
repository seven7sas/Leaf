package org.dreeam.leaf.anticheat.roomculling;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class UndergroundRoomCullingTestSuite {

    @BeforeAll
    public static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void testCoordinatePackingRoundtrip() {
        for (int dx = -64; dx <= 64; dx += 16) {
            for (int dy = -64; dy <= 64; dy += 16) {
                for (int dz = -64; dz <= 64; dz += 16) {
                    int packed = (dx + 64) | ((dy + 64) << 8) | ((dz + 64) << 16);
                    int unpackedX = (packed & 0xFF) - 64;
                    int unpackedY = ((packed >> 8) & 0xFF) - 64;
                    int unpackedZ = ((packed >> 16) & 0xFF) - 64;

                    Assertions.assertEquals(dx, unpackedX, "X coordinate mismatch");
                    Assertions.assertEquals(dy, unpackedY, "Y coordinate mismatch");
                    Assertions.assertEquals(dz, unpackedZ, "Z coordinate mismatch");
                }
            }
        }
    }

    @Test
    public void testBlockPermeability() {
        // Transparent / permeable blocks
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.AIR.defaultBlockState()), "AIR must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.WATER.defaultBlockState()), "WATER must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.LAVA.defaultBlockState()), "LAVA must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.GLASS.defaultBlockState()), "GLASS must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.WHITE_STAINED_GLASS.defaultBlockState()), "STAINED_GLASS must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.IRON_BARS.defaultBlockState()), "IRON_BARS must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.GLASS_PANE.defaultBlockState()), "GLASS_PANE must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.BARRIER.defaultBlockState()), "BARRIER must be permeable");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.TORCH.defaultBlockState()), "TORCH must be permeable");

        // Solid occluding blocks
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.STONE.defaultBlockState()), "STONE must occlude");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.DEEPSLATE.defaultBlockState()), "DEEPSLATE must occlude");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.DIRT.defaultBlockState()), "DIRT must occlude");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.OAK_PLANKS.defaultBlockState()), "PLANKS must occlude");

        // Slabs, stairs, walls, fences must occlude
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.OAK_SLAB.defaultBlockState()), "SLAB must occlude");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.OAK_STAIRS.defaultBlockState()), "STAIRS must occlude");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.OAK_FENCE.defaultBlockState()), "FENCE must occlude");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.COBBLESTONE_WALL.defaultBlockState()), "WALL must occlude");

        // Doors & Trapdoors: open vs closed
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true)), "Open door must be permeable");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false)), "Closed door must occlude");
        Assertions.assertTrue(RoomCullingService.isPermeable(Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true)), "Open trapdoor must be permeable");
        Assertions.assertFalse(RoomCullingService.isPermeable(Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, false)), "Closed trapdoor must occlude");
    }

    @Test
    public void testPlayerRoomVisibilityTracker() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        long key1 = SectionPos.asLong(10, 2, -5);
        long key2 = SectionPos.asLong(10, 3, -5);

        Assertions.assertFalse(tracker.isRevealed(key1), "Initially key1 should not be revealed");
        Assertions.assertFalse(tracker.isRevealed(key2), "Initially key2 should not be revealed");

        LongSet set = new LongOpenHashSet();
        set.add(key1);
        set.add(key2);
        tracker.revealSections(set);

        Assertions.assertTrue(tracker.isRevealed(key1), "key1 must be revealed");
        Assertions.assertTrue(tracker.isRevealed(key2), "key2 must be revealed");
        Assertions.assertEquals(2, tracker.getRevealedCount(), "Revealed count must be 2");

        tracker.reset();
        Assertions.assertFalse(tracker.isRevealed(key1), "After reset, key1 should not be revealed");
        Assertions.assertFalse(tracker.isRevealed(key2), "After reset, key2 should not be revealed");
        Assertions.assertEquals(0, tracker.getRevealedCount(), "Revealed count must be 0 after reset");
    }

    @Test
    public void testCulledSectionSingleValuePaletteSerialization() {
        // Test client PalettedContainer deserialization of SingleValuePalette
        ByteBuf buf = Unpooled.buffer();
        FriendlyByteBuf friendly = new FriendlyByteBuf(buf);

        // 1. Test STONE (Y >= 0): bitsPerEntry = 0, VarInt(STONE_ID)
        friendly.writeByte(0);
        friendly.writeVarInt(ClientboundLevelChunkPacketData.STONE_ID);

        PalettedContainer<BlockState> clientStatesStone = new PalettedContainer<>(
            Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        clientStatesStone.read(friendly);

        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), clientStatesStone.get(0, 0, 0), "Corner block must be STONE");
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), clientStatesStone.get(7, 7, 7), "Center block must be STONE");
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), clientStatesStone.get(15, 15, 15), "Far corner block must be STONE");

        // 2. Test DEEPSLATE (Y < 0): bitsPerEntry = 0, VarInt(DEEPSLATE_ID)
        buf.clear();
        friendly.writeByte(0);
        friendly.writeVarInt(ClientboundLevelChunkPacketData.DEEPSLATE_ID);

        PalettedContainer<BlockState> clientStatesDeepslate = new PalettedContainer<>(
            Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        clientStatesDeepslate.read(friendly);

        Assertions.assertEquals(Blocks.DEEPSLATE.defaultBlockState(), clientStatesDeepslate.get(0, 0, 0), "Corner block must be DEEPSLATE");
        Assertions.assertEquals(Blocks.DEEPSLATE.defaultBlockState(), clientStatesDeepslate.get(8, 8, 8), "Center block must be DEEPSLATE");
        Assertions.assertEquals(Blocks.DEEPSLATE.defaultBlockState(), clientStatesDeepslate.get(15, 15, 15), "Far corner block must be DEEPSLATE");
    }

    @Test
    public void testRaycastDDAOcclusionStopsAtWall() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        LongOpenHashSet newlyRevealed = new LongOpenHashSet();
        LongOpenHashSet chunksToRefresh = new LongOpenHashSet();

        // Tunnel of AIR from X=0..49, STONE wall at X=50, hidden room of AIR at X=51..100
        RoomCullingService.BlockGetterFunction world = (x, y, z) -> {
            if (x == 50) {
                return Blocks.STONE.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        };

        // Cast ray along +X direction
        int traversed = RoomCullingService.castSingleRayDDA(
            world, tracker,
            0.5, 0.5, 0.5,
            1.0, 0.0, 0.0,
            112, 56, -64,
            newlyRevealed, chunksToRefresh
        );

        // Ray must stop at the stone wall at X=50
        Assertions.assertEquals(51, traversed, "Ray must traverse exactly 51 blocks (0 to 50) and stop at stone wall");

        tracker.revealSections(newlyRevealed);

        // Sections inside the tunnel before the wall must be revealed
        long sec0 = SectionPos.asLong(0, 0, 0); // X: 0..15
        long sec1 = SectionPos.asLong(1, 0, 0); // X: 16..31
        long sec2 = SectionPos.asLong(2, 0, 0); // X: 32..47
        long sec3 = SectionPos.asLong(3, 0, 0); // X: 48..63 (contains the wall at X=50)

        Assertions.assertTrue(tracker.isRevealed(sec0), "Section 0 must be revealed");
        Assertions.assertTrue(tracker.isRevealed(sec1), "Section 1 must be revealed");
        Assertions.assertTrue(tracker.isRevealed(sec2), "Section 2 must be revealed");
        Assertions.assertTrue(tracker.isRevealed(sec3), "Section 3 (wall section) must be revealed");

        // Sections behind the wall MUST NOT be revealed!
        long sec4 = SectionPos.asLong(4, 0, 0); // X: 64..79 (hidden room)
        long sec5 = SectionPos.asLong(5, 0, 0); // X: 80..95 (hidden room)
        long sec6 = SectionPos.asLong(6, 0, 0); // X: 96..111 (hidden room)

        Assertions.assertFalse(tracker.isRevealed(sec4), "Section 4 behind wall must NOT be revealed");
        Assertions.assertFalse(tracker.isRevealed(sec5), "Section 5 behind wall must NOT be revealed");
        Assertions.assertFalse(tracker.isRevealed(sec6), "Section 6 behind wall must NOT be revealed");
    }

    @Test
    public void testCanBypassCachingAndReset() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();

        Assertions.assertFalse(tracker.getCachedBypass(), "Initial bypass must be false");
        Assertions.assertEquals(-100, tracker.getLastBypassCheckTick(), "Initial bypass check tick must be -100");

        // Set simulated cached bypass at tick 500
        tracker.setBypassCacheForTest(true, 500);
        Assertions.assertTrue(tracker.getCachedBypass(), "Cached bypass must be true");
        Assertions.assertEquals(500, tracker.getLastBypassCheckTick(), "Last check tick must be 500");

        // Reset should clear cache
        tracker.reset();
        Assertions.assertFalse(tracker.getCachedBypass(), "After reset, cached bypass must be false");
        Assertions.assertEquals(-100, tracker.getLastBypassCheckTick(), "After reset, last check tick must be -100");
    }

    @Test
    public void testEmptyAirSectionAndNullDataLayerNeverCulled() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();

        // 1. Null LevelLightEngine must return false for zero skylight (prevents culling)
        Assertions.assertFalse(RoomCullingService.isZeroSkylight(null, SectionPos.of(0, 0, 0)),
            "Null light engine must return false (open sky / StarLight safety)");

        // 2. hasOnlyAir() section must NEVER be culled
        PalettedContainer<BlockState> states = new PalettedContainer<>(
            Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        LevelChunkSection airSection = new LevelChunkSection(states, null);
        Assertions.assertTrue(airSection.hasOnlyAir(), "Section must have only air");

        boolean culledAir = RoomCullingService.isSectionCulledFast(tracker, null, 0, 0, 0, airSection);
        Assertions.assertFalse(culledAir, "Empty air section must NEVER be culled");

        // 3. Null section must NEVER be culled
        boolean culledNull = RoomCullingService.isSectionCulledFast(tracker, null, 0, 0, 0, null);
        Assertions.assertFalse(culledNull, "Null section must NEVER be culled");
    }

    @Test
    public void testForgetChunkRemovesSections() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        int chunkX = 7;
        int chunkZ = -12;
        int minSecY = -4;
        int maxSecY = 19;

        LongSet sections = new LongOpenHashSet();
        for (int sy = minSecY; sy <= maxSecY; sy++) {
            sections.add(SectionPos.asLong(chunkX, sy, chunkZ));
        }
        // Also add section from a different chunk that should NOT be forgotten
        long otherChunkSection = SectionPos.asLong(chunkX + 1, 0, chunkZ);
        sections.add(otherChunkSection);

        tracker.revealSections(sections);
        Assertions.assertEquals(maxSecY - minSecY + 2, tracker.getRevealedCount(), "Initial revealed count must match");

        // Forget chunk (7, -12)
        tracker.forgetChunk(chunkX, chunkZ, minSecY, maxSecY);

        // Sections of chunk (7, -12) should be gone
        for (int sy = minSecY; sy <= maxSecY; sy++) {
            long key = SectionPos.asLong(chunkX, sy, chunkZ);
            Assertions.assertFalse(tracker.isRevealed(key), "Section of forgotten chunk must not be revealed: sy=" + sy);
        }

        // Other chunk section must still be revealed
        Assertions.assertTrue(tracker.isRevealed(otherChunkSection), "Other chunk section must remain revealed");
        Assertions.assertEquals(1, tracker.getRevealedCount(), "Only 1 section should remain revealed after forgetChunk");
    }

    @Test
    public void testCulledChunksTrackingAndForgetChunk() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        long chunkKey1 = net.minecraft.world.level.ChunkPos.asLong(3, 4);
        long chunkKey2 = net.minecraft.world.level.ChunkPos.asLong(-5, 8);

        Assertions.assertFalse(tracker.wasChunkCulled(chunkKey1), "Chunk 1 should initially not be culled");
        Assertions.assertFalse(tracker.wasChunkCulled(chunkKey2), "Chunk 2 should initially not be culled");
        Assertions.assertEquals(0, tracker.getCulledChunkCount(), "Initial culled chunks count must be 0");

        tracker.markChunkCulled(chunkKey1);
        tracker.markChunkCulled(chunkKey2);
        Assertions.assertTrue(tracker.wasChunkCulled(chunkKey1), "Chunk 1 must be marked culled");
        Assertions.assertTrue(tracker.wasChunkCulled(chunkKey2), "Chunk 2 must be marked culled");
        Assertions.assertEquals(2, tracker.getCulledChunkCount(), "Culled chunks count must be 2");

        // Clear one chunk
        tracker.clearChunkCulled(chunkKey1);
        Assertions.assertFalse(tracker.wasChunkCulled(chunkKey1), "Chunk 1 must be cleared");
        Assertions.assertTrue(tracker.wasChunkCulled(chunkKey2), "Chunk 2 must still be culled");

        // Forget chunk (removes from culledChunks as well)
        tracker.forgetChunk(-5, 8, -4, 19);
        Assertions.assertFalse(tracker.wasChunkCulled(chunkKey2), "Chunk 2 must be removed by forgetChunk");
        Assertions.assertEquals(0, tracker.getCulledChunkCount(), "Culled chunks count must be 0 after forgetChunk");

        // Test reset()
        tracker.markChunkCulled(chunkKey1);
        Assertions.assertEquals(1, tracker.getCulledChunkCount(), "Culled count must be 1");
        tracker.reset();
        Assertions.assertEquals(0, tracker.getCulledChunkCount(), "Culled count must be 0 after reset");
        Assertions.assertFalse(tracker.wasChunkCulled(chunkKey1), "Chunk 1 must not be culled after reset");
    }

    @Test
    public void testRaycastSkipsUnculledChunksInRefreshList() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        LongOpenHashSet newlyRevealed = new LongOpenHashSet();
        LongOpenHashSet chunksToRefresh = new LongOpenHashSet();

        // Mark only chunk 1 as culled, chunk 0 and 2 are NOT culled
        long chunk0 = net.minecraft.world.level.ChunkPos.asLong(0, 0);
        long chunk1 = net.minecraft.world.level.ChunkPos.asLong(1, 0);
        tracker.markChunkCulled(chunk1);

        RoomCullingService.BlockGetterFunction world = (x, y, z) -> Blocks.AIR.defaultBlockState();

        // Cast ray through chunk 0 (X=0..15), chunk 1 (X=16..31), chunk 2 (X=32..47)
        RoomCullingService.castSingleRayDDA(
            world, tracker,
            0.5, 0.5, 0.5,
            1.0, 0.0, 0.0,
            40, 56, -64,
            newlyRevealed, chunksToRefresh
        );

        // Sections in all chunks should be marked as revealed in tracker
        Assertions.assertTrue(tracker.isRevealed(SectionPos.asLong(0, 0, 0)), "Chunk 0 section must be revealed");
        Assertions.assertTrue(tracker.isRevealed(SectionPos.asLong(1, 0, 0)), "Chunk 1 section must be revealed");
        Assertions.assertTrue(tracker.isRevealed(SectionPos.asLong(2, 0, 0)), "Chunk 2 section must be revealed");

        // But chunksToRefresh must ONLY contain chunk 1 because only chunk 1 was culled!
        Assertions.assertFalse(chunksToRefresh.contains(chunk0), "Chunk 0 was not culled, must NOT be refreshed");
        Assertions.assertTrue(chunksToRefresh.contains(chunk1), "Chunk 1 was culled, MUST be refreshed");
        Assertions.assertFalse(chunksToRefresh.contains(net.minecraft.world.level.ChunkPos.asLong(2, 0)), "Chunk 2 was not culled, must NOT be refreshed");
    }

    @Test
    public void testWaterBodyHeightmapSafety() {
        int worldSurface = 63;
        int oceanFloor = 30;

        // Section Y=2: Y ranges 32..47 (inside open ocean water)
        Assertions.assertTrue(RoomCullingService.isSectionInWaterBody(2, worldSurface, oceanFloor),
            "Section Y=2 (32..47) is inside ocean water and must NOT be culled");

        // Section Y=3: Y ranges 48..63 (top layer of ocean water)
        Assertions.assertTrue(RoomCullingService.isSectionInWaterBody(3, worldSurface, oceanFloor),
            "Section Y=3 (48..63) is inside ocean water and must NOT be culled");

        // Section Y=1: Y ranges 16..31 (intersects ocean floor at 30)
        Assertions.assertTrue(RoomCullingService.isSectionInWaterBody(1, worldSurface, oceanFloor),
            "Section Y=1 (16..31) intersects ocean floor (30) and must NOT be culled");

        // Section Y=0: Y ranges 0..15 (underground bunker below seabed floor 30)
        Assertions.assertFalse(RoomCullingService.isSectionInWaterBody(0, worldSurface, oceanFloor),
            "Section Y=0 (0..15) is deep below seabed (30) and can be safely culled");

        // Section Y=-1: Y ranges -16..-1 (deep underground bunker)
        Assertions.assertFalse(RoomCullingService.isSectionInWaterBody(-1, worldSurface, oceanFloor),
            "Section Y=-1 (-16..-1) is deep below seabed and can be safely culled");

        // Land terrain: worldSurface == oceanFloor == 70 (no open water body)
        Assertions.assertFalse(RoomCullingService.isSectionInWaterBody(2, 70, 70),
            "On land (surface == floor == 70), underground section Y=2 must NOT be treated as open water");
        Assertions.assertFalse(RoomCullingService.isSectionInWaterBody(0, 70, 70),
            "On land, deep underground section Y=0 must NOT be treated as open water");
    }

    @Test
    public void testSectionOcclusionBitmask() {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
            Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        LevelChunkSection section = new LevelChunkSection(states, null);

        // 1. All air section must return null mask
        Assertions.assertNull(section.leaf$getOcclusionMask(), "All-air section must return null mask");

        // 2. Set one stone block at (0, 0, 0)
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        long[] mask = section.leaf$getOcclusionMask();
        Assertions.assertNotNull(mask, "Section with stone must produce non-null mask");
        Assertions.assertEquals(64, mask.length, "Occlusion mask must have 64 longs");
        Assertions.assertTrue((mask[0] & 1L) != 0L, "Bit 0 must be 1 for stone at (0, 0, 0)");
        Assertions.assertTrue((mask[0] & 2L) == 0L, "Bit 1 must be 0 for air at (1, 0, 0)");

        // 3. Modifying block state must invalidate cached mask
        section.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
        Assertions.assertNull(section.leaf$getOcclusionMask(), "Mask must be invalidated back to null when block reverted to air");
    }

    @Test
    public void testSWMRNibbleArrayIsZeroLight() {
        // 1. Null data layer (StarLight INIT_STATE_NULL: open sky) must return false
        ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray nullArray =
            ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromVanilla(null);
        Assertions.assertFalse(nullArray.isZeroLight(), "INIT_STATE_NULL must return false (full skylight, not zero)");

        // 2. Empty vanilla data layer (INIT_STATE_UNINIT) must return true
        ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray emptyArray =
            ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromVanilla(new net.minecraft.world.level.chunk.DataLayer());
        Assertions.assertTrue(emptyArray.isZeroLight(), "Empty DataLayer must return true (zero light)");

        // 3. Initialized data layer with all zero bytes
        byte[] zeroBytes = new byte[2048];
        ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray zeroArray =
            ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromVanilla(new net.minecraft.world.level.chunk.DataLayer(zeroBytes));
        Assertions.assertTrue(zeroArray.isZeroLight(), "All-zero DataLayer must return true (zero light)");

        // 4. Initialized data layer with light data
        byte[] lightedBytes = new byte[2048];
        lightedBytes[42] = (byte) 0x0F;
        ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray lightedArray =
            ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromVanilla(new net.minecraft.world.level.chunk.DataLayer(lightedBytes));
        Assertions.assertFalse(lightedArray.isZeroLight(), "Lighted DataLayer must return false (has skylight)");
    }

    @Test
    public void testStampedLockOptimisticReadThreadSafety() throws InterruptedException {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        int threadCount = 4;
        int iterations = 10_000;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount + 1);
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        // Reader threads
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException ignored) {}

                while (running.get()) {
                    for (int i = 0; i < 50; i++) {
                        long key = SectionPos.asLong(i, 0, 0);
                        tracker.isRevealed(key);
                        tracker.wasChunkCulled(net.minecraft.world.level.ChunkPos.asLong(i, 0));
                    }
                }
            }).start();
        }

        // Writer thread
        Thread writer = new Thread(() -> {
            latch.countDown();
            try {
                latch.await();
            } catch (InterruptedException ignored) {}

            for (int it = 0; it < iterations; it++) {
                int secX = it % 50;
                long key = SectionPos.asLong(secX, 0, 0);
                long chunkPos = net.minecraft.world.level.ChunkPos.asLong(secX, 0);
                tracker.revealSection(key);
                tracker.markChunkCulled(chunkPos);
                if ((it & 15) == 0) {
                    tracker.clearChunkCulled(chunkPos);
                }
            }
            running.set(false);
        });

        writer.start();
        writer.join(10_000);
        running.set(false);

        Assertions.assertEquals(0, errors.get(), "Concurrent reads and writes must complete with 0 errors");
        Assertions.assertTrue(tracker.getRevealedCount() > 0, "Tracker must contain revealed sections");
    }

    @Test
    public void testWaveZAxisBoundaryIsolation() {
        // Test wave reaching lz = 15 in word w = 3 (ly = 0, lz = 12..15)
        // bitIndex for (lx=0, ly=0, lz=15) is (0 << 8) | (15 << 4) | 0 = 240
        // word index w = 240 >>> 6 = 3, bit in word = 240 & 63 = 48
        int w = 3;
        long f = 1L << 48; // wave at lz = 15

        // South (+Z) propagation:
        long south = f << 16; // 0L (overflows 64 bits)
        Assertions.assertEquals(0L, south, "Intra-word south shift must overflow 64 bits");

        long leakS = f >>> 48;
        Assertions.assertEquals(1L, leakS, "Leaked bits to South must be 1L (bit 0 in target word)");

        // Crucial invariant: (w & 3) == 3 must NEVER addWave to w + 1 in the current section!
        boolean intraSectionAllowed = (w & 3) < 3;
        Assertions.assertFalse(intraSectionAllowed, "Wave at lz=15 must NOT propagate to w+1 (ly+1, lz=0) of current section!");

        // Target word in neighbor section (secZ + 1) must be w - 3 = 0
        int targetWord = w - 3;
        Assertions.assertEquals(0, targetWord, "Target word in secZ+1 must be word 0 (ly=0, lz=0)");

        // If secZ+1 has solid stone wall at lz=0, the wall mask has bit 0 set (1L)
        long wallMask = 1L;
        long permeable = ~wallMask;
        long newBits = leakS & permeable;
        Assertions.assertEquals(0L, newBits, "Solid wall at lz=0 in neighbor section must completely stop the wave!");

        // Test North (-Z) boundary isolation: word w = 4 (ly = 1, lz = 0..3)
        // bitIndex for (lx=0, ly=1, lz=0) is (1 << 8) | (0 << 4) | 0 = 256
        // word index w = 256 >>> 6 = 4, bit in word = 256 & 63 = 0
        int wNorth = 4;
        long fNorth = 1L; // wave at lz = 0

        long north = fNorth >>> 16; // 0L
        Assertions.assertEquals(0L, north, "Intra-word north shift must be 0");

        long leakN = (fNorth & 0xFFFFL) << 48;
        Assertions.assertEquals(1L << 48, leakN, "Leaked bits to North must be at bit 48 (lz=15 in target word)");

        // Crucial invariant: (w & 3) == 0 must NEVER addWave to w - 1 in the current section!
        boolean intraSectionNorthAllowed = (wNorth & 3) > 0;
        Assertions.assertFalse(intraSectionNorthAllowed, "Wave at lz=0 must NOT propagate to w-1 (ly-1, lz=15) of current section!");

        int targetWordNorth = wNorth + 3;
        Assertions.assertEquals(7, targetWordNorth, "Target word in secZ-1 must be word 7 (ly=1, lz=15)");
    }

    @Test
    public void testSectionMaskRevisionGuardsAgainstStaleCache() {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
            Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        LevelChunkSection section = new LevelChunkSection(states, null);

        // Initial air section
        Assertions.assertNull(section.leaf$getOcclusionMask(), "Air section mask must be null");

        // Set stone block: increments revision and invalidates mask
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        long[] mask = section.leaf$getOcclusionMask();
        Assertions.assertNotNull(mask, "Section with stone must produce non-null mask");
        Assertions.assertTrue((mask[0] & 1L) != 0L, "Bit 0 must be set for stone at (0, 0, 0)");

        // Changing block to air again must invalidate mask
        section.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
        Assertions.assertNull(section.leaf$getOcclusionMask(), "Reverting to air must invalidate mask back to null");
    }

    @Test
    public void test64BitCulledSectionsMaskNoOverflow() {
        // Test section index 33 (> 32) does not overflow 32-bit int
        int highSectionIdx = 33;
        long mask = 1L << highSectionIdx;
        Assertions.assertNotEquals(0L, mask, "Mask for section 33 must not be 0");
        Assertions.assertTrue((mask & (1L << highSectionIdx)) != 0L, "Bit 33 must be set");
        // Verify 32-bit shift would have failed: (1 << 33) == (1 << 1) == 2
        Assertions.assertNotEquals(mask, (long)(1 << highSectionIdx), "64-bit shift must not match 32-bit overflow shift");
    }

    @Test
    public void testPermeableBitsTableMatchesSlowLogic() {
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            boolean fast = RoomCullingService.isPermeable(state);
            boolean slow = RoomCullingService.isPermeableSlow(state);
            Assertions.assertEquals(slow, fast, () -> "Permeability mismatch for blockstate: " + state);
        }
    }

    @Test
    public void testPaletteShortCircuitAndSmartInvalidation() {
        // 1. All-solid section (e.g. initial STONE)
        PalettedContainer<BlockState> statesSolid = new PalettedContainer<>(
            Blocks.STONE.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        LevelChunkSection sectionSolid = new LevelChunkSection(statesSolid, null);
        long[] maskSolid = sectionSolid.leaf$getOcclusionMask();
        Assertions.assertSame(LevelChunkSection.LEAF_ALL_SOLID_MASK, maskSolid,
            "Section containing only solid blocks must return LEAF_ALL_SOLID_MASK via palette short-circuit");

        // 2. Smart invalidation: replacing STONE with DEEPSLATE (both occluding/impermeable)
        sectionSolid.setBlockState(0, 0, 0, Blocks.DEEPSLATE.defaultBlockState());
        // Since permeability did not change (both false), leaf$occlusionMask must NOT be cleared!
        Assertions.assertSame(LevelChunkSection.LEAF_ALL_SOLID_MASK, sectionSolid.leaf$getOcclusionMask(),
            "Mask must not be invalidated when replacing one occluding block with another occluding block");

        // 3. Changing to AIR (permeability changes from false to true): must invalidate mask
        sectionSolid.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
        long[] maskMixed = sectionSolid.leaf$getOcclusionMask();
        Assertions.assertNotSame(LevelChunkSection.LEAF_ALL_SOLID_MASK, maskMixed,
            "Mask must be invalidated and recomputed when block changes permeability");
        Assertions.assertNotNull(maskMixed, "Mixed section must produce non-null custom mask");
        Assertions.assertEquals(0L, maskMixed[0] & 1L, "Bit 0 must be 0 for AIR at (0,0,0)");
    }

    @Test
    public void testThrottledChunkRefreshWithResetGeneration() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        int initialGen = tracker.getResetGeneration();

        long chunk1 = net.minecraft.world.level.ChunkPos.asLong(5, 10);
        long chunk2 = net.minecraft.world.level.ChunkPos.asLong(6, 10);

        tracker.enqueueChunkRefresh(chunk1);
        tracker.enqueueChunkRefresh(chunk2);
        // Duplicate enqueue should be ignored
        tracker.enqueueChunkRefresh(chunk1);

        Assertions.assertEquals(2, tracker.getPendingChunkRefreshCount(), "Pending queue must have 2 unique chunks");

        Long polled1 = tracker.pollPendingChunkRefresh();
        Assertions.assertEquals(chunk1, polled1.longValue(), "First polled chunk must match chunk1");
        Assertions.assertEquals(1, tracker.getPendingChunkRefreshCount(), "Queue must have 1 chunk left");

        // After polling chunk1, re-enqueuing chunk1 should succeed
        tracker.enqueueChunkRefresh(chunk1);
        Assertions.assertEquals(2, tracker.getPendingChunkRefreshCount(), "Re-enqueuing chunk1 must succeed");

        // Reset must increment generation and clear pending queue
        tracker.reset();
        Assertions.assertEquals(initialGen + 1, tracker.getResetGeneration(), "Reset must increment generation");
        Assertions.assertEquals(0, tracker.getPendingChunkRefreshCount(), "Reset must clear pending refresh queue");
    }

    @Test
    public void testExactChunkSectionMaskSelectiveRefresh() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();
        long chunkPosLong = net.minecraft.world.level.ChunkPos.asLong(3, 4);

        // Sections 0 and 1 are culled (mask = 0b11 = 3L)
        long initialMask = (1L << 0) | (1L << 1);
        tracker.setChunkCulledMask(chunkPosLong, initialMask);
        Assertions.assertEquals(initialMask, tracker.getChunkCulledMask(chunkPosLong));

        // 1. Revealing section 5 (not culled in this chunk): must return false
        long secKey5 = SectionPos.asLong(3, 5, 4);
        long secBit5 = 1L << 5;
        boolean shouldRefresh5 = tracker.revealSectionAndShouldRefresh(secKey5, chunkPosLong, secBit5);
        Assertions.assertFalse(shouldRefresh5, "Revealing unculled section 5 must not trigger refresh");
        Assertions.assertEquals(initialMask, tracker.getChunkCulledMask(chunkPosLong), "Culled mask must remain unchanged");

        // 2. Revealing section 1 (was culled): must return true and update mask to 0b01
        long secKey1 = SectionPos.asLong(3, 1, 4);
        long secBit1 = 1L << 1;
        boolean shouldRefresh1 = tracker.revealSectionAndShouldRefresh(secKey1, chunkPosLong, secBit1);
        Assertions.assertTrue(shouldRefresh1, "Revealing culled section 1 must trigger refresh");
        Assertions.assertEquals(1L << 0, tracker.getChunkCulledMask(chunkPosLong), "Remaining mask must be 0b01 (section 0)");

        // 3. Revealing section 1 again: must return false (already revealed)
        boolean shouldRefresh1Again = tracker.revealSectionAndShouldRefresh(secKey1, chunkPosLong, secBit1);
        Assertions.assertFalse(shouldRefresh1Again, "Duplicate reveal of section 1 must not trigger refresh");

        // 4. Revealing section 0 (last culled section): must return true and remove chunk from culled map
        long secKey0 = SectionPos.asLong(3, 0, 4);
        long secBit0 = 1L << 0;
        boolean shouldRefresh0 = tracker.revealSectionAndShouldRefresh(secKey0, chunkPosLong, secBit0);
        Assertions.assertTrue(shouldRefresh0, "Revealing last culled section 0 must trigger refresh");
        Assertions.assertEquals(0L, tracker.getChunkCulledMask(chunkPosLong), "Mask must be 0L after all sections revealed");
        Assertions.assertFalse(tracker.wasChunkCulled(chunkPosLong), "Chunk must no longer be in culled map");
    }

    @Test
    public void testQuantizedViewDirectionCache() {
        PlayerRoomVisibilityTracker tracker = new PlayerRoomVisibilityTracker();

        // Check getDirectionBin bounds [0, 255]
        int bin1 = PlayerRoomVisibilityTracker.getDirectionBin(0.0f, 0.0f);
        int bin2 = PlayerRoomVisibilityTracker.getDirectionBin(180.0f, -90.0f);
        int bin3 = PlayerRoomVisibilityTracker.getDirectionBin(-180.0f, 90.0f);
        Assertions.assertTrue(bin1 >= 0 && bin1 < 256, "bin1 out of range");
        Assertions.assertTrue(bin2 >= 0 && bin2 < 256, "bin2 out of range");
        Assertions.assertTrue(bin3 >= 0 && bin3 < 256, "bin3 out of range");

        // Test identical bins for small angular drift inside same bin
        int binYawA = PlayerRoomVisibilityTracker.getDirectionBin(5.0f, 0.0f);
        int binYawB = PlayerRoomVisibilityTracker.getDirectionBin(6.0f, 1.0f);
        Assertions.assertEquals(binYawA, binYawB, "Small angular changes inside same bin must map to same bin");

        // Test different bins when rotating to another bin
        int binYawC = PlayerRoomVisibilityTracker.getDirectionBin(25.0f, 0.0f);
        Assertions.assertNotEquals(binYawA, binYawC, "Rotating across bin boundaries must produce different bins");

        // Test mask operations
        long[] mask = tracker.getViewedDirectionMaskForTest();
        Assertions.assertEquals(0L, mask[binYawA >>> 6] & (1L << (binYawA & 63)), "Initial bit must be 0");

        // Mark bin viewed
        mask[binYawA >>> 6] |= (1L << (binYawA & 63));
        Assertions.assertTrue((mask[binYawA >>> 6] & (1L << (binYawA & 63))) != 0L, "Bit must be set after viewing");

        // Simulate reset on movement or reset()
        tracker.reset();
        mask = tracker.getViewedDirectionMaskForTest();
        Assertions.assertEquals(0L, mask[0], "Mask word 0 must be 0 after reset");
        Assertions.assertEquals(0L, mask[1], "Mask word 1 must be 0 after reset");
        Assertions.assertEquals(0L, mask[2], "Mask word 2 must be 0 after reset");
        Assertions.assertEquals(0L, mask[3], "Mask word 3 must be 0 after reset");
    }
}
