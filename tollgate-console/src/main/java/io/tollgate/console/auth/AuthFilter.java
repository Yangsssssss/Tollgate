package io.tollgate.console.auth;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 控制台 API 鉴权过滤器：/api/** 需要 JWT，注册与登录两个端点白名单放行。
 *
 * <p>只拦 /api 前缀——数据面 /v1 与控制面 /admin 的鉴权语义各自独立
 * （数据面按 API Key 哈希查快照，控制面按 JWT），不互相污染。
 */
@Component
public class AuthFilter implements WebFilter, Ordered {

    public static final String PRINCIPAL_ATTR = "console.principal";

    private final JwtService jwt;

    public AuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        boolean publicEndpoint = path.equals("/api/auth/login") || path.equals("/api/auth/register");
        if (publicEndpoint) {
            return chain.filter(exchange);
        }

        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(exchange, "缺少认证令牌");
        }
        JwtService.Principal principal = jwt.verify(header.substring(7).trim());
        if (principal == null) {
            return unauthorized(exchange, "令牌无效或已过期");
        }
        exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
        return chain.filter(exchange);
    }

    /** handler 侧从 exchange 取主体（过滤器保证：能走到这里的一定已认证） */
    public static JwtService.Principal principalOf(ServerWebExchange exchange) {
        return exchange.getRequiredAttribute(PRINCIPAL_ATTR);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":{\"message\":\"%s\",\"type\":\"auth_error\"}}"
                .formatted(message.replace("\"", "'"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
