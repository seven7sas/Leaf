package org.dreeam.leaf.async.path;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.apache.commons.lang3.Validate;

import java.util.ArrayDeque;

public final class NodeEvaluatorCache {

    private static final Object2ObjectOpenHashMap<NodeEvaluatorGenerator, Int2ObjectOpenHashMap<ArrayDeque<NodeEvaluator>>> cache = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<NodeEvaluator, NodeEvaluatorGenerator> nodeEvaluatorToGenerator = new Object2ObjectOpenHashMap<>();

    public static final ThreadLocal<BinaryHeap> HEAP_LOCAL = ThreadLocal.withInitial(BinaryHeap::new);
    public static final ThreadLocal<Node[]> NEIGHBORS_LOCAL = ThreadLocal.withInitial(() -> new Node[32]);

    private NodeEvaluatorCache() {
    }

    public static synchronized NodeEvaluator takeNodeEvaluator(NodeEvaluatorGenerator generator, NodeEvaluator localNodeEvaluator) {
        final int nodeEvaluatorFeatures = NodeEvaluatorFeatures.fromNodeEvaluator(localNodeEvaluator);
        Int2ObjectOpenHashMap<ArrayDeque<NodeEvaluator>> generatorMap = cache.computeIfAbsent(generator, k -> new Int2ObjectOpenHashMap<>());
        ArrayDeque<NodeEvaluator> deque = generatorMap.computeIfAbsent(nodeEvaluatorFeatures, k -> new ArrayDeque<>());
        NodeEvaluator nodeEvaluator = deque.poll();

        if (nodeEvaluator == null) {
            nodeEvaluator = generator.generate(NodeEvaluatorFeatures.unpack(nodeEvaluatorFeatures));
        }

        nodeEvaluatorToGenerator.put(nodeEvaluator, generator);

        return nodeEvaluator;
    }

    public static synchronized void returnNodeEvaluator(final NodeEvaluator nodeEvaluator) {
        final NodeEvaluatorGenerator generator = nodeEvaluatorToGenerator.remove(nodeEvaluator);
        Validate.notNull(generator, "NodeEvaluator already returned");

        final int feature = NodeEvaluatorFeatures.fromNodeEvaluator(nodeEvaluator);
        Int2ObjectOpenHashMap<ArrayDeque<NodeEvaluator>> generatorMap = cache.computeIfAbsent(generator, k -> new Int2ObjectOpenHashMap<>());
        ArrayDeque<NodeEvaluator> deque = generatorMap.computeIfAbsent(feature, k -> new ArrayDeque<>());
        if (deque.size() < 64) {
            deque.offer(nodeEvaluator);
        }
    }

    public static synchronized void clear() {
        cache.clear();
        nodeEvaluatorToGenerator.clear();
    }

    public static synchronized void removeNodeEvaluator(final NodeEvaluator nodeEvaluator) {
        nodeEvaluatorToGenerator.remove(nodeEvaluator);
    }
}
