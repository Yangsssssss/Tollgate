package io.tollgate.app.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.PATCH;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;

/**
 * 控制面路由（/admin 前缀）。与数据面完全独立的管护通道：
 * 车道/路由/密钥 CRUD + 快照刷新 + 熔断观测。
 *
 * <p>参数缺失、业务校验失败统一转 400；未捕获异常转 500——
 * 管理端点的调用方是人和脚本，报错要可读。
 */
@Configuration
public class AdminRouter {

    private final ObjectMapper mapper;

    public AdminRouter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    public RouterFunction<ServerResponse> adminRoutes(AdminController admin) {
        return RouterFunctions.route(GET("/admin/lanes"), req -> respond(admin.listLanes()))
                .andRoute(POST("/admin/lanes"), req -> withBody(req, admin::createLane))
                .andRoute(PATCH("/admin/lanes/{id}"),
                        req -> withBody(req, body -> admin.updateLane(pathId(req), body)))
                .andRoute(GET("/admin/routes"), req -> respond(admin.listRoutes()))
                .andRoute(PUT("/admin/routes"), req -> withBody(req, admin::upsertRoute))
                .andRoute(DELETE("/admin/routes/{alias}/{laneId}"), req ->
                        respond(admin.disableRoute(req.pathVariable("alias"), pathId(req))))
                .andRoute(GET("/admin/api-keys"), req -> respond(admin.listApiKeys()))
                .andRoute(POST("/admin/api-keys"), req -> withBody(req, admin::createApiKey))
                .andRoute(DELETE("/admin/api-keys/{id}"), req -> respond(admin.disableApiKey(pathId(req))))
                .andRoute(POST("/admin/refresh"), req -> respond(admin.refresh()))
                .andRoute(GET("/admin/breakers"), req -> respond(admin.breakers()))
                .andRoute(GET("/admin/recent-requests"), req -> respond(admin.recentRequests()));
    }

    private static long pathId(ServerRequest req) {
        try {
            return Long.parseLong(req.pathVariable("id"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("路径参数必须是数字: " + req.pathVariable("id"));
        }
    }

    /** 请求体解析失败 / 业务校验失败 → 400；其余异常 → 500 */
    private Mono<ServerResponse> respond(Mono<AdminController.ServerResponseHolder> work) {
        return work.flatMap(holder -> ServerResponse.status(
                        HttpStatus.valueOf(holder.status()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(holder.body()))
                .onErrorResume(IllegalArgumentException.class, e -> jsonError(400, e.getMessage()))
                .onErrorResume(e -> jsonError(500, "管理操作失败: " + e.getMessage()));
    }

    private Mono<ServerResponse> withBody(ServerRequest req,
                                          java.util.function.Function<JsonNode, Mono<AdminController.ServerResponseHolder>> fn) {
        return req.bodyToMono(String.class)
                .defaultIfEmpty("{}")
                .flatMap(raw -> {
                    try {
                        return respond(fn.apply(mapper.readTree(raw)));
                    } catch (Exception e) {
                        return jsonError(400, "请求体不是合法 JSON");
                    }
                });
    }

    private Mono<ServerResponse> jsonError(int status, String message) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
