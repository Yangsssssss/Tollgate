package io.tollgate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tollgate 启动入口。
 *
 * <p>模块职责：
 * <ul>
 *   <li>{@code tollgate-common}   —— 领域模型与错误码</li>
 *   <li>{@code tollgate-upstream} —— 上游协议适配</li>
 *   <li>{@code tollgate-core}     —— 调度内核</li>
 *   <li>{@code tollgate-metering} —— 计量与落账</li>
 *   <li>{@code tollgate-app}      —— 组装、数据面入口、控制面 API</li>
 * </ul>
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "io.tollgate")
public class TollgateApplication {

    public static void main(String[] args) {
        SpringApplication.run(TollgateApplication.class, args);
    }
}
