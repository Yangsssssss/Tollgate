package io.tollgate.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tollgate.console.auth.AuthFilter;
import io.tollgate.console.auth.AuthService;
import io.tollgate.console.auth.JwtService;
import io.tollgate.console.keys.ApiKeyManagementService;
import io.tollgate.console.usage.UsageService;
import io.tollgate.metering.BillingService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;

/**
 * 控制台路由：/api 前缀。函数式风格与数据面/控制面一致，全部对外端点一处声明。
 *
 * <p>所有 handler 内的 JDBC 查询都是阻塞调用，统一 subscribeOn(boundedElastic)
 * ——事件循环线程上跑阻塞 IO 会掐住整个网关的吞吐（与落账 flusher 的约定同源）。
 */
@Component
public class ConsoleRouter {

    private final AuthService auth;
    private final ApiKeyManagementService keys;
    private final UsageService usage;
    private final BillingService billing;
    private final ObjectMapper mapper;

    public ConsoleRouter(AuthService auth, ApiKeyManagementService keys,
                         UsageService usage, BillingService billing, ObjectMapper mapper) {
        this.auth = auth;
        this.keys = keys;
        this.usage = usage;
        this.billing = billing;
        this.mapper = mapper;
    }

    // @formatter:off
    @org.springframework.context.annotation.Bean
    public RouterFunction<ServerResponse> consoleRoutes() {
        return RouterFunctions.route()
                .route(POST("/api/auth/register").and(accept(MediaType.APPLICATION_JSON)), this::register)
                .route(POST("/api/auth/login").and(accept(MediaType.APPLICATION_JSON)), this::login)
                .route(GET("/api/me"), this::me)
                .route(GET("/api/keys"), this::listKeys)
                .route(POST("/api/keys").and(accept(MediaType.APPLICATION_JSON)), this::createKey)
                .route(POST("/api/keys/{id}/disable"), this::disableKey)
                .route(GET("/api/usage"), this::usageReport)
                .route(GET("/api/balance/ledger"), this::ledger)
                .route(GET("/api/tariffs"), this::tariffs)
                .build();
    }
    // @formatter:on

    // ---- 认证 ----

    private Mono<ServerResponse> register(ServerRequest request) {
        return withBody(request, body -> {
            AuthService.AuthResult result = auth.register(
                    body.path("username").asText(null), body.path("password").asText(null));
            return ok(Map.of("token", result.token(), "username", result.username(),
                    "balanceMicros", result.balanceMicros()));
        });
    }

    private Mono<ServerResponse> login(ServerRequest request) {
        return withBody(request, body -> {
            AuthService.AuthResult result = auth.login(
                    body.path("username").asText(null), body.path("password").asText(null));
            return ok(Map.of("token", result.token(), "username", result.username(),
                    "balanceMicros", result.balanceMicros()));
        });
    }

    private Mono<ServerResponse> me(ServerRequest request) {
        return blocking(() -> {
            var principal = AuthFilter.principalOf(request.exchange());
            long balance = billing.balanceOf(principal.tenantId());
            return ok(Map.of("userId", principal.userId(), "username", principal.username(),
                    "tenantId", principal.tenantId(), "balanceMicros", balance));
        });
    }

    // ---- 密钥 ----

    private Mono<ServerResponse> listKeys(ServerRequest request) {
        return blocking(() -> {
            var principal = AuthFilter.principalOf(request.exchange());
            return ok(Map.of("keys", keys.list(principal.tenantId())));
        });
    }

    private Mono<ServerResponse> createKey(ServerRequest request) {
        return withBody(request, body -> {
            var principal = AuthFilter.principalOf(request.exchange());
            var created = keys.create(principal.tenantId(), body.path("name").asText(null));
            return ok(Map.of(
                    "key", created.view(),
                    "plaintext", created.plaintext(),
                    "hint", "明文仅此一次展示，请立即保存。新密钥约 30 秒内生效（快照刷新周期），也可调 POST /admin/refresh 立即生效"));
        });
    }

    private Mono<ServerResponse> disableKey(ServerRequest request) {
        return blocking(() -> {
            var principal = AuthFilter.principalOf(request.exchange());
            long keyId = Long.parseLong(request.pathVariable("id"));
            boolean done = keys.disable(principal.tenantId(), keyId);
            return done ? ok(Map.of("ok", true))
                    : ServerResponse.status(404).contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("error", Map.of("message", "密钥不存在")));
        });
    }

    // ---- 用量与余额 ----

    private Mono<ServerResponse> usageReport(ServerRequest request) {
        return blocking(() -> {
            var principal = AuthFilter.principalOf(request.exchange());
            int days = parseInt(request.queryParam("days").orElse("7"), 7);
            return ok(Map.of("report", usage.report(principal.tenantId(), days)));
        });
    }

    private Mono<ServerResponse> ledger(ServerRequest request) {
        return blocking(() -> {
            var principal = AuthFilter.principalOf(request.exchange());
            int limit = parseInt(request.queryParam("limit").orElse("50"), 50);
            return ok(Map.of("ledger", usage.recentLedger(principal.tenantId(), limit)));
        });
    }

    private Mono<ServerResponse> tariffs(ServerRequest request) {
        return blocking(() -> ok(Map.of("tariffs", usage.tariffs())));
    }

    // ---- 基建 ----

    private interface BlockingCall<T> {
        T run();
    }

    /**
     * 阻塞查询统一搬到 boundedElastic：fromCallable 的 supplier 在 boundedElastic
     * 线程上求值（JDBC 阻塞发生在这里），产出的 Mono 再被 flatMap 展开。
     */
    private Mono<ServerResponse> blocking(java.util.function.Supplier<Mono<ServerResponse>> call) {
        return Mono.fromCallable(call::get)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(m -> m)
                .onErrorResume(IllegalArgumentException.class, e -> badRequest(e.getMessage()))
                .onErrorResume(e -> ServerResponse.status(500).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", Map.of("message", "控制台内部错误: " + e.getMessage()))));
    }

    private Mono<ServerResponse> withBody(ServerRequest request,
                                          java.util.function.Function<JsonNode, Mono<ServerResponse>> fn) {
        return request.bodyToMono(String.class)
                .defaultIfEmpty("{}")
                .flatMap(raw -> {
                    try {
                        return fn.apply(mapper.readTree(raw));
                    } catch (Exception e) {
                        return badRequest("请求体不是合法 JSON");
                    }
                })
                .onErrorResume(IllegalArgumentException.class, e -> badRequest(e.getMessage()))
                .onErrorResume(e -> ServerResponse.status(500).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", Map.of("message", "控制台内部错误: " + e.getMessage()))));
    }

    private static Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", Map.of("message", message)));
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
