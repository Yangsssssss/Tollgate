package io.tollgate.console.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 *
 * <p>选 JWT 而不是服务端 session：控制台 API 无状态，网关本身不引入
 * 会话存储；令牌载荷只带 userId/tenantId/username 三个不可变字段，
 * 余额等易变数据每次实时查询，不用 JWT 缓存。
 *
 * <p>密钥从配置读取（tollgate.console.jwt-secret），生产必须显式配置；
 * 未配置时用内置开发密钥并打警告——本地演示可跑，密钥强度不达标。
 */
@Component
public class JwtService {

    public record Principal(long userId, long tenantId, String username) {
    }

    private static final String DEV_SECRET = "tollgate-dev-only-secret-key-change-in-production!!";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${tollgate.console.jwt-secret:}") String secret,
                      @Value("${tollgate.console.jwt-ttl-hours:72}") long ttlHours) {
        String effective = (secret == null || secret.isBlank()) ? DEV_SECRET : secret;
        if ((secret == null || secret.isBlank()) && Boolean.parseBoolean(
                System.getenv().getOrDefault("TOLLGATE_PROD", "false"))) {
            throw new IllegalStateException("生产环境必须配置 tollgate.console.jwt-secret");
        }
        this.key = Keys.hmacShaKeyFor(effective.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String issue(Principal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principal.username())
                .claim("uid", principal.userId())
                .claim("tid", principal.tenantId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析令牌。
     *
     * @return 主体；令牌无效/过期返回 null（由调用方决定 401）
     */
    public Principal verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return new Principal(
                    claims.get("uid", Long.class),
                    claims.get("tid", Long.class),
                    claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
