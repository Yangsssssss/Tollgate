package io.tollgate.core.dispatch;

import io.tollgate.core.lane.Lane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 候选集解析：把"模型 → 可用车道有序列表"这件事拆成两层过滤。
 *
 * <p><b>第一层（本类）</b>：结构性的过滤与排序——
 * <ol>
 *   <li>优先级分层：priority 数值越小越优先，低优先级层只在高优先级层全灭后兜底
 *       （故障转移发生在调用层逐个尝试，这里只负责给出尝试顺序）</li>
 *   <li>层内平滑加权轮询：同层车道按权重摊流量，而不是随机或固定顺序</li>
 *   <li>健康度预筛：熔断 OPEN 状态的车道直接跳过——这是廉价的非消耗性读，
 *       真正的许可发放（tryAcquire，会消耗半开探测配额）在调用层</li>
 * </ol>
 *
 * <p><b>selector 缓存与失效</b>：SWRR 是有状态算法，每个"模型 × 优先级层"
 * 需要一个独立实例（见 {@link SmoothWeightedSelector} 的锁粒度说明）。
 * 配置变更（车道增删、权重调整）通过快照签名检测：车道列表的 (id, weight) 指纹
 * 变化即整组重建。控制面改配置后，下一个请求自动生效，无需手动刷新。
 */
public final class CandidateResolver {

    /** 车道目录：由 app 层实现（配置缓存 → 查库），core 只依赖此抽象 */
    @FunctionalInterface
    public interface LaneDirectory {
        List<Lane> lanesForModel(String model);
    }

    private final LaneDirectory directory;
    /** 熔断 OPEN 的车道判定，由 LaneSelector 注入 */
    private final Predicate<Lane> laneOpen;
    private final ConcurrentHashMap<String, ModelRoutes> cache = new ConcurrentHashMap<>();

    public CandidateResolver(LaneDirectory directory, Predicate<Lane> laneOpen) {
        this.directory = directory;
        this.laneOpen = laneOpen;
    }

    /**
     * 解析模型的候选车道，按尝试顺序返回。
     *
     * @return 有序候选；模型无车道或全部熔断时返回空列表
     */
    public List<Lane> candidates(String model) {
        List<Lane> lanes = directory.lanesForModel(model);
        if (lanes == null || lanes.isEmpty()) {
            return List.of();
        }
        ModelRoutes routes = cache.compute(model, (k, existing) ->
                existing != null && existing.matches(lanes) ? existing : new ModelRoutes(lanes));

        List<Lane> result = new ArrayList<>(lanes.size());
        for (Map.Entry<Integer, List<Lane>> tier : routes.tiers.entrySet()) {
            List<Lane> tierLanes = tier.getValue();
            SmoothWeightedSelector<Lane> selector = routes.selectorFor(tier.getKey(), tierLanes);

            // SWRR 决定本层从谁开始，其余车道按稳定顺序跟进；
            // 再滤掉熔断中的车道。先轮询后过滤，保证 selector 的状态推进
            // 与"车道是否可用"解耦——熔断的车道不消费轮询位置。
            Lane head = selector.next();
            List<Lane> ordered = new ArrayList<>(tierLanes.size());
            int idx = tierLanes.indexOf(head);
            for (int i = 0; i < tierLanes.size(); i++) {
                ordered.add(tierLanes.get(Math.floorMod(idx + i, tierLanes.size())));
            }
            ordered.remove(head);
            ordered.add(0, head);

            for (Lane lane : ordered) {
                if (!laneOpen.test(lane)) {
                    result.add(lane);
                }
            }
        }
        return result;
    }

    /** 一个模型的路由快照：签名 + 各优先级层的 SWRR selector */
    private static final class ModelRoutes {
        private final List<String> signature;
        private final Map<Integer, List<Lane>> tiers;
        private final Map<Integer, SmoothWeightedSelector<Lane>> selectors = new ConcurrentHashMap<>();

        ModelRoutes(List<Lane> lanes) {
            this.signature = lanes.stream()
                    .map(l -> l.id() + ":" + l.weight())
                    .sorted()
                    .toList();
            Map<Integer, List<Lane>> grouped = new java.util.TreeMap<>();
            for (Lane lane : lanes) {
                grouped.computeIfAbsent(lane.priority(), k -> new ArrayList<>()).add(lane);
            }
            grouped.values().forEach(tier -> tier.sort(Comparator.comparingLong(Lane::id)));
            this.tiers = grouped;
        }

        boolean matches(List<Lane> lanes) {
            List<String> current = lanes.stream()
                    .map(l -> l.id() + ":" + l.weight())
                    .sorted()
                    .toList();
            return current.equals(signature);
        }

        SmoothWeightedSelector<Lane> selectorFor(int priority, List<Lane> tierLanes) {
            return selectors.computeIfAbsent(priority, p -> {
                List<SmoothWeightedSelector.Weighted<Lane>> weighted = tierLanes.stream()
                        .map(l -> new SmoothWeightedSelector.Weighted<>(l, l.weight()))
                        .toList();
                return SmoothWeightedSelector.of(weighted);
            });
        }
    }
}
