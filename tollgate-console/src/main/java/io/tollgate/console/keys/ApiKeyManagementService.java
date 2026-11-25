package io.tollgate.console.keys;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 用户自助密钥管理。安全语义与 V1 一致：明文只在创建响应里出现一次，
 * 库内只存 SHA-256；key_prefix 用于列表识别。
 *
 * <p>新密钥要等 CatalogSnapshot 下一次刷新（定时 30s 或手动 /admin/refresh）
 * 才会被数据面认证接受——这是快照架构的固有延迟，前端需要提示。
 */
@Service
public class ApiKeyManagementService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public ApiKeyManagementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ApiKeyView(long id, String name, String keyPrefix,
                             String status, String createdAt) {
    }

    public record CreatedKey(ApiKeyView view, String plaintext) {
    }

    public List<ApiKeyView> list(long tenantId) {
        return jdbc.query("""
                SELECT id, name, key_prefix, status, created_at
                FROM api_key WHERE tenant_id = ? ORDER BY id DESC
                """, (rs, i) -> new ApiKeyView(
                rs.getLong("id"), rs.getString("name"), rs.getString("key_prefix"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString()), tenantId);
    }

    public CreatedKey create(long tenantId, String name) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("密钥名称须为 1-64 个字符");
        }
        byte[] bytes = new byte[15];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes); // 30 hex chars
        String prefix = "tg-" + hex.substring(0, 8);
        String plaintext = prefix + hex.substring(8);
        String hash = sha256Hex(plaintext);
        try {
            jdbc.update("""
                    INSERT INTO api_key (tenant_id, name, key_prefix, key_hash) VALUES (?, ?, ?, ?)
                    """, tenantId, name.trim(), prefix, hash);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("密钥哈希碰撞，请重试", e);
        }
        Long id = jdbc.queryForObject(
                "SELECT id FROM api_key WHERE key_hash = ?", Long.class, hash);
        String createdAt = jdbc.queryForObject(
                "SELECT created_at FROM api_key WHERE id = ?",
                (rs, i) -> rs.getTimestamp(1).toInstant().toString(), id);
        return new CreatedKey(new ApiKeyView(id, name.trim(), prefix, "ACTIVE", createdAt), plaintext);
    }

    /** 吊销 = 置 DISABLED。快照刷新后认证查不到该哈希，网关侧 401 生效 */
    public boolean disable(long tenantId, long keyId) {
        return jdbc.update(
                "UPDATE api_key SET status = 'DISABLED' WHERE id = ? AND tenant_id = ?",
                keyId, tenantId) > 0;
    }

    private static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
