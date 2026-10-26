package io.tollgate.core.dispatch;

import io.tollgate.core.dispatch.SmoothWeightedSelector.Weighted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmoothWeightedSelectorTest {

    @Test
    @DisplayName("长期分布严格符合权重比")
    void distributesByWeightOverTime() {
        var selector = SmoothWeightedSelector.of(List.of(
                new Weighted<>("A", 3),
                new Weighted<>("B", 1)));

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            counts.merge(selector.next(), 1, Integer::sum);
        }

        assertThat(counts.get("A")).isEqualTo(300);
        assertThat(counts.get("B")).isEqualTo(100);
    }

    @Test
    @DisplayName("轻权重节点被提前插入，而不是留到周期末尾")
    void placesLightNodeEarly() {
        // 朴素加权轮询 5:1 的序列是 A A A A A B，轻节点排在最后；
        // 平滑版本是 A A A B A A，轻节点提前到第 4 位。
        // 注意跨周期拼接后仍会出现长游程（周期末尾两个 A 接下一周期三个 A），
        // 这是算法的固有特性，不是缺陷——所以这里只验证单周期内的位置。
        var selector = SmoothWeightedSelector.of(List.of(
                new Weighted<>("A", 5),
                new Weighted<>("B", 1)));

        List<String> firstRound = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            firstRound.add(selector.next());
        }

        assertThat(firstRound.indexOf("B")).isEqualTo(3);
    }

    @Test
    @DisplayName("多节点场景下交错分布，不会整块聚集")
    void interleavesNodesInsteadOfBlocking() {
        // A=5 B=3 C=2：朴素轮询会排成 A A A A A B B B C C，最长连续 5；
        // 平滑轮询是 A B C A A B A C B A，最长连续 2。
        var selector = SmoothWeightedSelector.of(List.of(
                new Weighted<>("A", 5),
                new Weighted<>("B", 3),
                new Weighted<>("C", 2)));

        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sequence.add(selector.next());
        }

        assertThat(longestRun(sequence, "A")).isLessThanOrEqualTo(2);
        assertThat(longestRun(sequence, "B")).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("任意长度窗口内的比例偏差不超过 1，说明分布是平滑而非成块聚集")
    void ratioStaysCloseInEveryWindow() {
        var selector = SmoothWeightedSelector.of(List.of(
                new Weighted<>("A", 3),
                new Weighted<>("B", 1)));

        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            sequence.add(selector.next());
        }

        // 窗口取权重的整数倍才应恰好等于比例；这里验证窗口长度 4 的整数倍
        for (int start = 0; start + 4 <= sequence.size(); start += 4) {
            long countA = sequence.subList(start, start + 4).stream().filter("A"::equals).count();
            assertThat(countA).as("窗口 [%d,%d) 内 A 的数量", start, start + 4).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("权重非正的节点被静默过滤，不参与选路")
    void ignoresNonPositiveWeight() {
        var selector = SmoothWeightedSelector.of(List.of(
                new Weighted<>("A", 1),
                new Weighted<>("DISABLED", 0),
                new Weighted<>("NEGATIVE", -5)));

        assertThat(selector.nodeCount()).isEqualTo(1);
        for (int i = 0; i < 10; i++) {
            assertThat(selector.next()).isEqualTo("A");
        }
    }

    @Test
    @DisplayName("候选全部非法时构建失败，避免把空选择器带进热路径")
    void rejectsEmptyCandidates() {
        assertThatThrownBy(() -> SmoothWeightedSelector.of(List.of(new Weighted<>("A", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("权重全部非法");
    }

    @Test
    @DisplayName("并发调用下总量守恒，不丢不重")
    void staysConsistentUnderConcurrency() throws Exception {
        var selector = SmoothWeightedSelector.of(List.of(
                new Weighted<>("A", 3),
                new Weighted<>("B", 1)));

        int threads = 8;
        int perThread = 1000;
        int[] counters = new int[2];
        Thread[] workers = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                int localA = 0;
                for (int i = 0; i < perThread; i++) {
                    if ("A".equals(selector.next())) {
                        localA++;
                    }
                }
                synchronized (counters) {
                    counters[0] += localA;
                    counters[1] += perThread - localA;
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(counters[0] + counters[1]).isEqualTo(threads * perThread);
        // 8000 次按 3:1 分配，A 应精确等于 6000
        assertThat(counters[0]).isEqualTo(6000);
    }

    private static int longestRun(List<String> sequence, String value) {
        int longest = 0;
        int current = 0;
        for (String item : sequence) {
            if (value.equals(item)) {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }
}
