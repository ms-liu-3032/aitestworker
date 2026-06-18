package com.company.aitest.trace;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class WorkerDeviceService {
    private static final String BIND_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int BIND_CODE_LENGTH = 8;
    private static final int WORKER_TOKEN_BYTES = 32;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public WorkerDeviceService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public BindCodeResponse createBindCode(CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        LocalDateTime expiresAt = now.plusMinutes(10);
        String code = newBindCode();
        String codeHash = sha256(code);

        jdbcTemplate.update("""
                insert into worker_bind_code(user_id, code_hash, status, expires_at, created_at, updated_at)
                values (?, ?, 'PENDING', ?, ?, ?)
                """, user.id(), codeHash, expiresAt, now, now);

        return new BindCodeResponse(code, expiresAt);
    }

    @Transactional
    public WorkerDeviceRecord bind(String deviceName, String platform, String arch,
                                    String workerVersion, String protocolVersion, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String tokenHash = sha256(newWorkerToken());
        jdbcTemplate.update("""
                insert into worker_device(user_id, device_name, platform, arch, worker_version,
                  protocol_version, bind_status, worker_token_hash, last_seen_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'BOUND', ?, ?, ?, ?)
                """, user.id(), deviceName, platform, arch, workerVersion, protocolVersion,
                tokenHash, now, now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getById(id, user);
    }

    @Transactional
    public WorkerBindResponse consumeBindCode(String code, String deviceName, String platform, String arch,
                                              String workerVersion, String protocolVersion) {
        LocalDateTime now = timeProvider.now();
        String normalizedCode = normalizeBindCode(code);
        String codeHash = sha256(normalizedCode);

        BindCodeRow bindCode = jdbc.sql("""
                select * from worker_bind_code
                where code_hash = :codeHash and status = 'PENDING'
                """)
                .param("codeHash", codeHash)
                .query(this::mapBindCode)
                .optional()
                .orElseThrow(() -> new BusinessException("绑定码不存在或已使用，请在主平台重新生成绑定码"));

        if (bindCode.expiresAt().isBefore(now)) {
            jdbcTemplate.update("update worker_bind_code set status = 'EXPIRED', updated_at = ? where id = ?",
                    now, bindCode.id());
            throw new BusinessException("绑定码已过期，请在主平台重新生成绑定码");
        }

        String workerToken = newWorkerToken();
        String workerTokenHash = sha256(workerToken);
        jdbcTemplate.update("""
                insert into worker_device(user_id, device_name, platform, arch, worker_version,
                  protocol_version, bind_status, worker_token_hash, last_seen_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'BOUND', ?, ?, ?, ?)
                """, bindCode.userId(), deviceName, platform, arch, workerVersion, protocolVersion,
                workerTokenHash, now, now, now);
        Long deviceId = jdbc.sql("select last_insert_id()").query(Long.class).single();
        jdbcTemplate.update("""
                update worker_bind_code
                set status = 'CONSUMED', consumed_at = ?, device_id = ?, updated_at = ?
                where id = ?
                """, now, deviceId, now, bindCode.id());

        return new WorkerBindResponse(deviceId, workerToken, "BOUND", now);
    }

    @Transactional
    public WorkerHeartbeatResponse heartbeat(String workerToken) {
        LocalDateTime now = timeProvider.now();
        String token = normalizeToken(workerToken);
        Long deviceId = jdbc.sql("""
                select id from worker_device
                where worker_token_hash = :tokenHash and bind_status = 'BOUND'
                """)
                .param("tokenHash", sha256(token))
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException("本地采集器凭证无效，请重新绑定设备"));
        jdbcTemplate.update("update worker_device set last_seen_at = ?, updated_at = ? where id = ?",
                now, now, deviceId);
        return new WorkerHeartbeatResponse(deviceId, now);
    }

    public List<WorkerDeviceRecord> list(CurrentUser user) {
        return jdbc.sql("select * from worker_device where user_id = :userId order by id desc")
                .param("userId", user.id()).query(this::map).list();
    }

    @Transactional
    public void revoke(Long id, CurrentUser user) {
        WorkerDeviceRecord device = getById(id, user);
        if (!device.userId().equals(user.id())) {
            throw new BusinessException("只能撤销自己的设备");
        }
        jdbcTemplate.update("update worker_device set bind_status = 'REVOKED', updated_at = ? where id = ?",
                timeProvider.now(), id);
    }

    private WorkerDeviceRecord getById(Long id, CurrentUser user) {
        return jdbc.sql("select * from worker_device where id = :id and user_id = :userId")
                .param("id", id).param("userId", user.id()).query(this::map).optional()
                .orElseThrow(() -> new BusinessException("设备不存在"));
    }

    private String placeholderHash() {
        return sha256(newWorkerToken());
    }

    private String newBindCode() {
        StringBuilder sb = new StringBuilder(BIND_CODE_LENGTH);
        for (int i = 0; i < BIND_CODE_LENGTH; i++) {
            sb.append(BIND_CODE_ALPHABET.charAt(secureRandom.nextInt(BIND_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String newWorkerToken() {
        byte[] bytes = new byte[WORKER_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String normalizeBindCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException("绑定码不能为空");
        }
        return code.trim().replace("-", "").replace(" ", "").toUpperCase();
    }

    private String normalizeToken(String workerToken) {
        if (workerToken == null || workerToken.trim().isEmpty()) {
            throw new BusinessException("worker_token 不能为空");
        }
        String token = workerToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return token.substring(7).trim();
        }
        return token;
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    private BindCodeRow mapBindCode(ResultSet rs, int rowNum) throws SQLException {
        return new BindCodeRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getTimestamp("expires_at").toLocalDateTime()
        );
    }

    private WorkerDeviceRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new WorkerDeviceRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("device_name"),
                rs.getString("platform"),
                rs.getString("arch"),
                rs.getString("worker_version"),
                rs.getString("protocol_version"),
                rs.getString("bind_status"),
                null,
                rs.getTimestamp("last_seen_at") != null ? rs.getTimestamp("last_seen_at").toLocalDateTime() : null,
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private record BindCodeRow(Long id, Long userId, String status, LocalDateTime expiresAt) {
    }

    public record BindCodeResponse(String code, LocalDateTime expiresAt) {
    }

    public record WorkerBindResponse(Long deviceId, String workerToken, String bindStatus, LocalDateTime serverTime) {
    }

    public record WorkerHeartbeatResponse(Long deviceId, LocalDateTime lastSeenAt) {
    }
}
