package io.tollgate.app.dataplane;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;

/**
 * 数据面路由。对外只暴露 OpenAI 兼容协议——这是网关的对外契约，
 * 控制面 API 走独立前缀（/admin），两者互不干扰。
 */
@Configuration
public class DataPlaneRouter {

    @Bean
    public RouterFunction<ServerResponse> dataPlaneRoutes(ChatCompletionsHandler handler) {
        return RouterFunctions.route(
                POST("/v1/chat/completions").and(accept(MediaType.APPLICATION_JSON)),
                handler::chatCompletions);
    }
}
