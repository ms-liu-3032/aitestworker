package com.company.aitest.trace;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrowserProfileService {
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;
    private static final String AES_GCM = "AES/GCM/NoPadding";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final SecretKey secretKey;

    public BrowserProfileService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                  @Value("${app.secret-key:}") String secretKeyStr) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        if (secretKeyStr != null && !secretKeyStr.isBlank()) {
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(secretKeyStr);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("app.secret-key 不是合法的 Base64 字符串");
            }
            if (!(keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32)) {
                throw new BusinessException("app.secret-key 长度非法，解码后必须是 16/24/32 字节");
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } else {
            this.secretKey = null;
        }
    }

    private String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        if (secretKey == null) throw new BusinessException("密钥未配置，无法保存密码");
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("密码加密失败", e);
        }
    }

    private String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) return null;
        if (secretKey == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[GCM_IV_LEN];
            byte[] encrypted = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public BrowserProfileRecord create(Long projectId, String profileName, String targetHost,
                                        String accountLabel, String roleLabel,
                                        String username, String password, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String passwordCipher = encrypt(password);
        jdbcTemplate.update("""
                insert into browser_profile(project_id, user_id, profile_name, target_host,
                  account_label, role_label, username, password_cipher, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, projectId, user.id(), profileName, targetHost, accountLabel, roleLabel,
                username, passwordCipher, now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return redactPasswordCipher(getById(projectId, id, user));
    }

    public List<BrowserProfileRecord> list(Long projectId, CurrentUser user) {
        return jdbc.sql("""
                select * from browser_profile
                where project_id = :projectId and user_id = :userId
                order by id desc
                """).param("projectId", projectId).param("userId", user.id()).query(this::map).list()
                .stream()
                .map(this::redactPasswordCipher)
                .toList();
    }

    public BrowserProfileRecord get(Long projectId, Long profileId, CurrentUser user) {
        return redactPasswordCipher(getById(projectId, profileId, user));
    }

    @Transactional
    public BrowserProfileRecord update(Long projectId, Long profileId, String profileName, String targetHost, String accountLabel,
                                       String roleLabel, String username, String password, String status,
                                       CurrentUser user) {
        BrowserProfileRecord existing = getById(projectId, profileId, user);
        String nextStatus = status == null || status.isBlank() ? existing.status() : status.trim();
        ensureProfileStatus(nextStatus);
        LocalDateTime now = timeProvider.now();
        String nextPasswordCipher = password != null ? encrypt(password) : existing.passwordCipher();
        jdbcTemplate.update("""
                update browser_profile
                set profile_name = ?, target_host = ?, account_label = ?, role_label = ?,
                  username = ?, password_cipher = ?, status = ?, updated_at = ?
                where id = ? and user_id = ?
                """,
                valueOrCurrent(profileName, existing.profileName()),
                valueOrCurrent(targetHost, existing.targetHost()),
                valueOrCurrent(accountLabel, existing.accountLabel()),
                valueOrCurrent(roleLabel, existing.roleLabel()),
                valueOrCurrent(username, existing.username()),
                nextPasswordCipher,
                nextStatus,
                now,
                profileId,
                user.id());
        return redactPasswordCipher(getById(projectId, profileId, user));
    }

    @Transactional
    public BrowserProfileOperationRecord logOperation(Long projectId, Long profileId, String operationType,
                                                       String operationDetail, CurrentUser user) {
        getById(projectId, profileId, user);
        ensureOperationType(operationType);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into browser_profile_operation(profile_id, project_id, user_id, operation_type,
                  operation_detail, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, profileId, projectId, user.id(), operationType, operationDetail, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        if ("OPEN".equals(operationType) || "START_RECORD".equals(operationType)) {
            jdbcTemplate.update("update browser_profile set last_used_at = ?, updated_at = ? where id = ?",
                    now, now, profileId);
        }
        return jdbc.sql("select * from browser_profile_operation where id = :id")
                .param("id", id).query(this::mapOperation).single();
    }

    public BrowserProfileRecord getById(Long profileId, CurrentUser user) {
        return jdbc.sql("select * from browser_profile where id = :id and user_id = :userId")
                .param("id", profileId).param("userId", user.id()).query(this::map).optional()
                .orElseThrow(() -> new BusinessException("身份空间不存在"));
    }

    private BrowserProfileRecord getById(Long projectId, Long profileId, CurrentUser user) {
        return jdbc.sql("""
                select * from browser_profile
                where id = :id and project_id = :projectId and user_id = :userId
                """)
                .param("id", profileId).param("projectId", projectId).param("userId", user.id())
                .query(this::map).optional()
                .orElseThrow(() -> new BusinessException("身份空间不存在"));
    }

    private String valueOrCurrent(String value, String current) {
        return value == null ? current : value;
    }

    private void ensureOperationType(String operationType) {
        if (operationType == null) {
            throw new BusinessException("身份空间操作类型不能为空");
        }
        try {
            BrowserProfileOperationType.valueOf(operationType);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("身份空间操作类型不支持");
        }
    }

    private void ensureProfileStatus(String status) {
        if (status == null) {
            throw new BusinessException("身份空间状态不能为空");
        }
        try {
            BrowserProfileStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("身份空间状态不支持");
        }
    }

    public BrowserProfileController.ProfileCredentialsResponse getCredentials(Long projectId, Long profileId, CurrentUser user) {
        BrowserProfileRecord profile = getById(projectId, profileId, user);
        return new BrowserProfileController.ProfileCredentialsResponse(
                profile.username(), decrypt(profile.passwordCipher()));
    }

    private BrowserProfileRecord redactPasswordCipher(BrowserProfileRecord record) {
        return new BrowserProfileRecord(
                record.id(),
                record.projectId(),
                record.userId(),
                record.profileName(),
                record.targetHost(),
                record.accountLabel(),
                record.roleLabel(),
                record.username(),
                null,
                record.storageStatePath(),
                record.status(),
                record.lastUsedAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private BrowserProfileRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new BrowserProfileRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("user_id"),
                rs.getString("profile_name"),
                rs.getString("target_host"),
                rs.getString("account_label"),
                rs.getString("role_label"),
                rs.getString("username"),
                rs.getString("password_cipher"),
                rs.getString("storage_state_path"),
                rs.getString("status"),
                rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toLocalDateTime() : null,
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private BrowserProfileOperationRecord mapOperation(ResultSet rs, int rowNum) throws SQLException {
        return new BrowserProfileOperationRecord(
                rs.getLong("id"),
                rs.getLong("profile_id"),
                rs.getLong("project_id"),
                rs.getLong("user_id"),
                rs.getString("operation_type"),
                rs.getString("operation_detail"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
