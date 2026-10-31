package org.dreeam.leaf.util.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.BitSet;
import java.util.function.IntFunction;

public class CachedOrNewBitsGetter {

    private static final IntFunction<BitSet> BITSET_CONSTRUCTOR = BitSet::new;
    private static final ThreadLocal<Int2ObjectOpenHashMap<BitSet>> BITSETS = ThreadLocal.withInitial(Int2ObjectOpenHashMap::new);

    private CachedOrNewBitsGetter() {
    }

    public static BitSet getCachedOrNewBitSet(int bits) {
        Int2ObjectOpenHashMap<BitSet> map = BITSETS.get();
        if (map.size() > 64) {
            map.clear();
        }
        final BitSet bitSet = map.computeIfAbsent(bits, BITSET_CONSTRUCTOR);

        bitSet.clear();

        return bitSet;
    }
}
