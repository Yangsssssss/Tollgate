package io.tollgate.core.keypool;

import java.time.Duration;

/**
 * 密钥池配置。
 *
 * @param consecutiveFailureThreshold 连续失败多少次后封禁该密钥。
 *                                    用"连续"而非滑动窗口，是因为密钥失效（余额耗尽、被上游封禁）
 *                                    是持续状态而不是概率抖动，连续失败是更强的信号
 * @param banDuration                 封禁时长。到期自动解除并允许一次真实调用去验证——
 *                                    不做永久封禁，因为余额充值、风控解除都可能发生
 */
public record KeyPoolConfig(
        int consecutiveFailureThreshold,
        Duration banDuration
) {

    public KeyPoolConfig {
        if (consecutiveFailureThreshold < 1) {
            throw new IllegalArgumentException("consecutiveFailureThreshold 必须 >= 1");
        }
        if (banDuration.isNegative() || banDuration.isZero()) {
            throw new IllegalArgumentException("banDuration 必须为正");
        }
    }

    public static KeyPoolConfig defaults() {
        return new KeyPoolConfig(3, Duration.ofSeconds(60));
    }
}
