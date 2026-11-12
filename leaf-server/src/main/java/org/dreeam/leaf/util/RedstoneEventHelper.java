package org.dreeam.leaf.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.block.BlockRedstoneEvent;

/**
 * Shared fast-path helpers for Bukkit {@link BlockRedstoneEvent} dispatch.
 * Avoids {@link CraftBlock} and {@link BlockRedstoneEvent} allocations when no plugins listen.
 */
public final class RedstoneEventHelper {

    private RedstoneEventHelper() {
    }

    public static boolean hasListeners() {
        return BlockRedstoneEvent.getHandlerList().getRegisteredListeners().length != 0;
    }

    public static int callRedstoneChange(Level level, BlockPos pos, int oldCurrent, int newCurrent) {
        if (!hasListeners()) {
            return newCurrent;
        }
        BlockRedstoneEvent event = new BlockRedstoneEvent(CraftBlock.at(level, pos), oldCurrent, newCurrent);
        event.callEvent();
        return event.getNewCurrent();
    }

    public static boolean callBinaryRedstoneChange(Level level, BlockPos pos, boolean willBePowered) {
        if (!hasListeners()) {
            return true;
        }
        int oldCurrent = willBePowered ? 0 : 15;
        int newCurrent = willBePowered ? 15 : 0;
        return callRedstoneChange(level, pos, oldCurrent, newCurrent) == newCurrent;
    }
}
