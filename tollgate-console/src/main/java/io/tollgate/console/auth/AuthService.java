package io.tollgate.console.auth;

import io.tollgate.metering.BillingService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 注册与登录。注册即建租户：一人一租户是产品化的隔离边界——
 * 限流（RPM/TPM）、密钥、余额、账单全部天然按人隔离，不需要再设计组织结构。
 * 新租户赠送演示额度（tollgate.console.signup-grant-micros，默认 ¥5），
 * 走 BillingEngine.credit 统一落流水。
 */
@Service
public class AuthService {

    public record AuthResult(long userId, long tenantId, String username,
                             long balanceMicros, String token) {
    }

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final BillingService billing;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final long signupGrantMicros;

    public AuthService(JdbcTemplate jdbc, JwtService jwtService, BillingService billing,
                       @org.springframework.beans.factory.annotation.Value(
                               "${tollgate.console.signup-grant-micros:5000000}") long signupGrantMicros) {
        this.jdbc = jdbc;
        this.jwt = jwtService;
        this.billing = billing;
        this.signupGrantMicros = signupGrantMicros;
    }

    public AuthResult register(String username, String password) {
        validateUsername(username);
        validatePassword(password);
        String hash = encoder.encode(password);
        try {
            jdbc.update("INSERT INTO tenant (code, name) VALUES (?, ?)",
                    "u_" + username, username);
        } catch (DuplicateKeyException e) {
            // tenant.code 与 username 一一映射，撞唯一键即用户名已注册
            throw new IllegalArgumentException("用户名已被注册");
        }
        Long tenantId = jdbc.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, "u_" + username);
        long userId;
        try {
            jdbc.update("""
                    INSERT INTO console_user (tenant_id, username, password_hash)
                    VALUES (?, ?, ?)
                    """, tenantId, username, hash);
            userId = jdbc.queryForObject(
                    "SELECT id FROM console_user WHERE username = ?", Long.class, username);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("用户名已被注册");
        }
        // 注册赠送：走计费引擎统一记账（内存 + 流水 + DB 同步）
        billing.credit(tenantId, signupGrantMicros, "SIGNUP_GRANT", "grant-" + UUID.randomUUID());
        var principal = new JwtService.Principal(userId, tenantId, username);
        return new AuthResult(userId, tenantId, username, signupGrantMicros, jwt.issue(principal));
    }

    public AuthResult login(String username, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.tenant_id, u.password_hash, u.status
                FROM console_user u WHERE u.username = ?
                """, username);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        Map<String, Object> row = rows.get(0);
        if (!"ACTIVE".equals(row.get("status"))) {
            throw new IllegalArgumentException("账号已停用");
        }
        if (!encoder.matches(password, (String) row.get("password_hash"))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        long userId = ((Number) row.get("id")).longValue();
        long tenantId = ((Number) row.get("tenant_id")).longValue();
        long balance = billing.balanceOf(tenantId);
        var principal = new JwtService.Principal(userId, tenantId, username);
        return new AuthResult(userId, tenantId, username, balance, jwt.issue(principal));
    }

    private void validateUsername(String username) {
        if (username == null || !username.matches("[a-zA-Z0-9_]{3,32}")) {
            throw new IllegalArgumentException("用户名须为 3-32 位字母、数字或下划线");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new IllegalArgumentException("密码长度须为 6-64 位");
        }
    }
}
