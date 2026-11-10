<img src="public/image/leaf_banner.png" alt="Leaf">

# Fork Leaf (1.21.11)

Performance fork of [Winds-Studio/Leaf](https://github.com/Winds-Studio/Leaf) and [Paper](https://papermc.io/) for Minecraft 1.21.11 (`seven7sas/Leaf`).

Original patches and additions in this fork are licensed under [GPL-3.0-only](LICENSE.md). Anyone reusing code or patches from this repository must keep copyright notices, credit `https://github.com/seven7sas/Leaf`, and distribute derivative works under GPL-3.0.

## What changed from upstream

### Storage and memory
- Added `LINEAR_V3` region format (`LinearV3RegionFile`) using Zstandard compression with thread-local JNI context pooling (`LinearV3ZstdContextPool`) and automatic migration (`LinearV3MigrationHelper`) to avoid native memory leaks and G1GC humongous allocations. Added `-DLeaf.linear-v2-read-only=true` inspection mode.
- Added experimental lazy item NBT decoding for chests, trapped chests, barrels, and shulker boxes (`ContainerItemLazyLoading`, `LithiumInventory`) that defers item parsing until the inventory is opened.
- Cleared `ThreadLocal` buffer references after cloning in `ClientboundLightUpdatePacketData` and fixed array retention in `CachedOrNewBitsGetter`.

### Chunk loading and player join
- Prioritized chunk sending along the player's movement vector (`PredictiveChunkConfig` velocity cone) in `AsyncChunkSender` and `RegionizedPlayerChunkLoader`.
- Ramped up chunk loading, ticking, and sending queues on join, respawn, and long teleports without shrinking `SingleUserAreaMap` radii (avoids unloading and re-sending already loaded chunks).
- Moved `player.dat` (`PlayerDataStorage`), `stats/<uuid>.json` (`ServerStatsCounter`), and `advancements/<uuid>.json` (`PlayerAdvancements`) disk reads, decompression, `MCDataConverter` / `DataFixer` passes, and JSON parsing onto `AsyncPlayerDataSaving.IO_POOL` virtual threads during login handshake, with generation checks, pending-save waiting, TTL eviction, and disconnect cleanup.
- Cached `PalettedContainerFactory` per `RegistryAccess.Frozen` and reused `level.palettedContainerFactory()` in `LevelChunk`.
- Linked `Criterion` trigger instance `hashCode()` at datapack load, cached `hashCode()` inside `CriterionTrigger.Listener`, and skipped listener allocation for `ImpossibleTrigger`.
- Cached `leaf$poiType` directly on `BlockStateBase` and replaced `sectionPos.blocksInside()` stream/Optional usage in `PoiManager#updateFromSection` with a direct 0..15 loop.
- Replaced full-world entity list scans in `ChunkMap` with tracked entity collections (`leaf$trackedEntities`).

### Async systems and classloading
- Added off-thread mob brain sensor evaluation (`ParallelBrainSensingDispatcher`) and thread-safe pathfinding node evaluator caching (`NodeEvaluatorCache`, `WalkNodeEvaluator`, `AmphibiousNodeEvaluator`).
- Added split-phase parallel entity push collisions (`ParallelCollisionDispatcher`).
- Allowed non-blocking async reads for `ServerLevel#getEntities`, `CraftWorld`, and `CraftEntity#getNearbyEntities` by removing main-thread `AsyncCatcher` guards on reads and hardening `ChunkEntitySlices` array iteration against concurrent swap-remove mutations.
- Added generation-versioned miss and defining-loader caches (`LookupCache`) in `SimpleListPluginClassLoaderGroup`, `SpigotPluginClassLoaderGroup`, and `DependencyBasedPluginClassLoaderGroup` to eliminate repeated `ClassNotFoundException` scans across plugins.

### Tick loop and misc fixes
- Added option to skip `PlayerMoveEvent` on pure camera rotation (yaw/pitch change only). Enabled `Leaf.enableFMA` by default.
- Optimized Purpur lagging check to read only the 5-second TPS buffer (`getTPS5s()`).
- Added early return in `ServerLevel#tickPrecipitation` when not raining and the block below is not water.
- Wrapped async mob spawn state calculation (`ServerChunkCache`) in `try-catch-finally`, and added world task draining plus main-thread ownership restoration in `MinecraftServer` parallel world ticking.

## Config (`config/leaf-global.yml`)

- `async.parallel-brain-sensing` (default `true`) - run mob brain sensing off-thread when entity counts are high
- `async.parallel-collisions` (default `true`) - split-phase parallel entity push collision checks
- `perf.chunk-loading.predictive-velocity-cone-send` (default `true`) - prioritize chunks in movement direction
- `perf.chunk-loading.smooth-spawn-ramp-up` (default `true`) - ramp up spawn/teleport chunk queues gradually
- `perf.skip-move-event-on-rotation-only` (default `true`) - skip `PlayerMoveEvent` when position did not change
- `perf.container-item-lazy-loading` (default `false`) - defer decoding container items until inventory access
- `misc.region-format.format-name` - `MCA`, `B_LINEAR`, `LINEAR_V2`, or `LINEAR_V3`

## Building

Requires JDK 21:

```bash
./gradlew applyAllPatches && ./gradlew :leaf-server:createMojmapBundlerJar
```

Or build a Paperclip jar:

```bash
./gradlew applyAllPatches && ./gradlew createMojmapPaperclipJar
```

## License

Additions and modifications in this fork are Copyright (c) 2026 seven7sas and licensed under [GPL-3.0-only](LICENSE.md).
Upstream Leaf, Paper, and Paperweight components keep their original licenses (`MIT`, `GPL-3.0`, `LGPL-3.0`, `Apache-2.0`); see [LICENSE.md](LICENSE.md) and [`licenses/`](licenses/).

## Credits

Based on [Leaf](https://github.com/Winds-Studio/Leaf), [Paper](https://papermc.io/), [Gale](https://github.com/GaleMC/Gale), [Pufferfish](https://github.com/pufferfish-gg/Pufferfish), [Purpur](https://github.com/PurpurMC/Purpur), and the upstream projects credited in Leaf (`Mirai`, `Petal`, `Leaves`, `SparklyPaper`, `Luminol`, `Moonrise`).
