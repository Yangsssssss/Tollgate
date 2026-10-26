package io.tollgate.core.dispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 平滑加权轮询（Smooth Weighted Round-Robin），Nginx upstream 采用的经典算法。
 *
 * <p>解决的问题：朴素加权轮询会把高权重节点连续排在一起（权重 5:1 时序列是
 * {@code A A A A A B}），突发的连续流量会压垮单个节点。平滑版本把同样的比例
 * 摊开成 {@code A A B A A A}，任意前缀内的分布都接近权重比。
 *
 * <p>算法每次选择做三件事：
 * <ol>
 *   <li>所有节点的 currentWeight 各自加上自身 weight</li>
 *   <li>取 currentWeight 最大者为本次选中节点</li>
 *   <li>选中节点减去 totalWeight，使其"回落"</li>
 * </ol>
 *
 * <p><b>为什么用 synchronized</b>：SWRR 是全局状态机，每个节点的 currentWeight
 * 都依赖上一次选择的结果，无法用无锁方式表达。临界区是一次长度为 N 的遍历加几次
 * 整数加减，N 通常是个位数，耗时在纳秒级。真正的风险不是锁本身，而是锁的粒度——
 * 如果所有模型共用同一个实例，不同模型的选路会互相阻塞。所以调度层要为每个模型
 * 维护独立的 selector 实例。
 *
 * @param <T> 节点载荷类型
 */
public final class SmoothWeightedSelector<T> {

    private final List<Node<T>> nodes;
    private final int totalWeight;

    private SmoothWeightedSelector(List<Node<T>> nodes, int totalWeight) {
        this.nodes = nodes;
        this.totalWeight = totalWeight;
    }

    /**
     * 由带权重的节点列表构建。权重 <= 0 的节点会被丢弃——它们在语义上等同于禁用，
     * 保留下来只会让 totalWeight 失真。
     */
    public static <T> SmoothWeightedSelector<T> of(List<Weighted<T>> candidates) {
        List<Node<T>> nodes = new ArrayList<>(candidates.size());
        int total = 0;
        for (Weighted<T> candidate : candidates) {
            if (candidate.weight() <= 0) {
                continue;
            }
            nodes.add(new Node<>(candidate.item(), candidate.weight()));
            total += candidate.weight();
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("候选车道权重全部非法，无法构建选择器");
        }
        return new SmoothWeightedSelector<>(nodes, total);
    }

    /**
     * 选择下一个节点。
     *
     * @return 选中的节点载荷；正常不会返回 null（构建时已保证非空）
     */
    public synchronized T next() {
        Node<T> best = null;
        for (Node<T> node : nodes) {
            node.currentWeight += node.weight;
            // 严格大于才替换：平局时保留遍历顺序靠前的节点，保证结果稳定可预期
            if (best == null || node.currentWeight > best.currentWeight) {
                best = node;
            }
        }
        best.currentWeight -= totalWeight;
        return best.item;
    }

    /** 便于观测：导出当前各节点的 currentWeight，用于验证分布是否符合权重比 */
    public synchronized List<String> snapshot() {
        List<String> result = new ArrayList<>(nodes.size());
        for (Node<T> node : nodes) {
            result.add("%s:w=%d,cw=%d".formatted(node.item, node.weight, node.currentWeight));
        }
        return result;
    }

    public int nodeCount() {
        return nodes.size();
    }

    private static final class Node<T> {
        private final T item;
        private final int weight;
        private int currentWeight;

        private Node(T item, int weight) {
            this.item = item;
            this.weight = weight;
        }
    }

    /** 带权重的候选，不可变 */
    public record Weighted<T>(T item, int weight) {
    }
}
