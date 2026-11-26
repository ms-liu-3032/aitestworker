package com.company.aitest.dsh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshConnectionService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public DshConnectionService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public BindCode createBindCode(CurrentUser user) {
        if (user == null || user.id() == null) throw new BusinessException("请先登录");
        String code = randomCode();
        LocalDateTime now = timeProvider.now();
        LocalDateTime expiresAt = now.plusMinutes(10);
        jdbcTemplate.update("UPDATE dsh_bind_code SET status = 'REVOKED' WHERE user_id = ? AND status = 'PENDING'", user.id());
        jdbcTemplate.update("""
                INSERT INTO dsh_bind_code(user_id, code_hash, status, expires_at, created_at)
                VALUES (?, ?, 'PENDING', ?, ?)
                """, user.id(), sha256(code), expiresAt, now);
        return new BindCode(code, expiresAt);
    }

    @Transactional
    public Binding bind(String code, String deviceName) {
        if (code == null || code.isBlank() || deviceName == null || deviceName.isBlank()) {
            throw new BusinessException("绑定码和设备名称不能为空");
        }
        String normalized = code.replace("-", "").replace(" ", "").trim().toUpperCase();
        if (normalized.length() != 12 || deviceName.trim().length() > 128) {
            throw new BusinessException("绑定码或设备名称格式错误");
        }
        LocalDateTime now = timeProvider.now();
        var rows = jdbc.sql("""
                SELECT bc.id, bc.user_id FROM dsh_bind_code bc
                JOIN sys_user u ON u.id = bc.user_id AND u.status = 'ACTIVE'
                WHERE bc.code_hash = :hash AND bc.status = 'PENDING' AND bc.expires_at > :now
                """)
                .param("hash", sha256(normalized)).param("now", now)
                .query((rs, rowNum) -> new long[]{rs.getLong("id"), rs.getLong("user_id")}).list();
        if (rows.isEmpty()) throw new BusinessException("绑定码无效、已使用或已过期");
        long[] row = rows.get(0);
        int consumed = jdbcTemplate.update("""
                UPDATE dsh_bind_code SET status = 'CONSUMED', consumed_at = ?
                WHERE id = ? AND status = 'PENDING' AND expires_at > ?
                """, now, row[0], now);
        if (consumed != 1) throw new BusinessException("绑定码已使用，请重新获取");
        String userToken = randomToken("dsh_user_");
        String agentToken = randomToken("dsh_agent_");
        jdbcTemplate.update("""
                INSERT INTO dsh_connection(user_id, device_name, user_token_hash, agent_token_hash, status, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """, row[1], deviceName.trim().substring(0, Math.min(deviceName.trim().length(), 128)),
                sha256(userToken), sha256(agentToken), now);
        Long connectionId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcTemplate.update("UPDATE dsh_bind_code SET connection_id = ? WHERE id = ?", connectionId, row[0]);
        return new Binding(connectionId, userToken, agentToken, row[1]);
    }

    public DshPrincipal authenticate(String token, boolean userSession) {
        if (token == null || !token.startsWith(userSession ? "dsh_user_" : "dsh_agent_")) return null;
        String tokenColumn = userSession ? "user_token_hash" : "agent_token_hash";
        var result = jdbc.sql("""
                SELECT c.id AS connection_id, u.id, u.username, u.role_code
                FROM dsh_connection c JOIN sys_user u ON u.id = c.user_id
                WHERE c.%s = :hash AND c.status = 'ACTIVE' AND u.status = 'ACTIVE'
                """.formatted(tokenColumn))
                .param("hash", sha256(token))
                .query((rs, rowNum) -> new DshPrincipal(rs.getLong("connection_id"),
                        new CurrentUser(rs.getLong("id"), rs.getString("username"), rs.getString("role_code"))))
                .optional().orElse(null);
        if (result != null) {
            jdbcTemplate.update("""
                    UPDATE dsh_connection SET last_used_at = ?
                    WHERE id = ? AND (last_used_at IS NULL OR last_used_at < ?)
                    """, timeProvider.now(), result.connectionId(), timeProvider.now().minusMinutes(5));
        }
        return result;
    }

    public List<ConnectionView> list(CurrentUser user) {
        return jdbc.sql("""
                SELECT id, device_name, status, last_used_at, created_at FROM dsh_connection
                WHERE user_id = :userId ORDER BY id DESC
                """).param("userId", user.id())
                .query((rs, rowNum) -> new ConnectionView(rs.getLong("id"), rs.getString("device_name"),
                        rs.getString("status"), rs.getTimestamp("last_used_at") == null ? null
                                : rs.getTimestamp("last_used_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    @Transactional
    public void revoke(Long connectionId, CurrentUser user) {
        int updated = jdbcTemplate.update("""
                UPDATE dsh_connection SET status = 'REVOKED', revoked_at = ?
                WHERE id = ? AND user_id = ? AND status = 'ACTIVE'
                """, timeProvider.now(), connectionId, user.id());
        if (updated != 1) throw new BusinessException("连接不存在或已撤销");
    }

    private String randomCode() {
        StringBuilder result = new StringBuilder(12);
        for (int i = 0; i < 12; i++) result.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return result.toString();
    }

    private String randomToken(String prefix) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + HexFormat.of().formatHex(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record BindCode(String code, LocalDateTime expiresAt) {}
    // Both credentials authenticate requests sent by local DSH to this AITestWork backend.
    // userToken carries the bound user's authority; agentToken is restricted to model-facing tool routes.
    public record Binding(Long connectionId, String userToken, String agentToken, Long userId) {}
    public record ConnectionView(Long id, String deviceName, String status,
                                 LocalDateTime lastUsedAt, LocalDateTime createdAt) {}
    public record DshPrincipal(Long connectionId, CurrentUser user) {}
}
