package com.company.aitest.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceRulePackConfigService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public TraceRulePackConfigService(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                      TimeProvider timeProvider, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    public List<TraceRulePackConfigRecord> listPacks(Long projectId) {
        return jdbc.sql("""
                SELECT * FROM trace_rule_pack_config
                WHERE project_id IS NULL OR project_id = :projectId
                ORDER BY CASE WHEN project_id = :projectId THEN 0 ELSE 1 END,
                         status = 'ACTIVE' DESC, priority DESC, updated_at DESC, id DESC
                """)
                .param("projectId", projectId)
                .query(this::mapRecord)
                .list();
    }

    public TraceRulePackConfigRecord getPack(Long packId) {
        List<TraceRulePackConfigRecord> results = jdbc.sql("SELECT * FROM trace_rule_pack_config WHERE id = :id")
                .param("id", packId)
                .query(this::mapRecord)
                .list();
        return results.isEmpty() ? null : results.get(0);
    }

    @Transactional
    public TraceRulePackConfigRecord createPack(Long projectId, CreateRulePackCommand command, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String packKey = normalizePackKey(command.packKey());
        String packName = normalizeRequired(command.packName(), "规则包名称不能为空");
        String packType = normalizeWithDefault(command.packType(), "TRACE_CLEANING");
        String status = normalizeStatus(command.status());
        String configJson = normalizeConfigJson(command.configJson());
        jdbcTemplate.update("""
                INSERT INTO trace_rule_pack_config(project_id, pack_key, pack_name, pack_type, version,
                    status, priority, config_json, description, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    id = LAST_INSERT_ID(id),
                    pack_name = VALUES(pack_name),
                    pack_type = VALUES(pack_type),
                    status = VALUES(status),
                    priority = VALUES(priority),
                    config_json = VALUES(config_json),
                    description = VALUES(description),
                    updated_at = VALUES(updated_at)
                """, projectId, packKey, packName, packType, status,
                command.priority() == null ? 0 : command.priority(),
                configJson, blankToNull(command.description()),
                user == null ? null : user.id(), now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getPack(id);
    }

    @Transactional
    public TraceRulePackConfigRecord updatePack(Long projectId, Long packId, UpdateRulePackCommand command, CurrentUser user) {
        requireProjectPack(projectId, packId);
        String packName = command.packName() == null ? null : normalizeRequired(command.packName(), "规则包名称不能为空");
        String packType = command.packType() == null ? null : normalizeWithDefault(command.packType(), "TRACE_CLEANING");
        String status = command.status() == null ? null : normalizeStatus(command.status());
        String configJson = command.configJson() == null ? null : normalizeConfigJson(command.configJson());
        jdbcTemplate.update("""
                UPDATE trace_rule_pack_config SET
                    pack_name = COALESCE(?, pack_name),
                    pack_type = COALESCE(?, pack_type),
                    status = COALESCE(?, status),
                    priority = COALESCE(?, priority),
                    config_json = COALESCE(?, config_json),
                    description = COALESCE(?, description),
                    version = version + CASE WHEN ? IS NULL THEN 0 ELSE 1 END,
                    updated_at = ?
                WHERE id = ?
                """, packName, packType, status, command.priority(), configJson,
                blankToNull(command.description()), configJson, timeProvider.now(), packId);
        return getPack(packId);
    }

    @Transactional
    public void activate(Long projectId, Long packId) {
        updateStatus(projectId, packId, "ACTIVE");
    }

    @Transactional
    public void deactivate(Long projectId, Long packId) {
        updateStatus(projectId, packId, "INACTIVE");
    }

    @Transactional
    public void archive(Long projectId, Long packId) {
        updateStatus(projectId, packId, "ARCHIVED");
    }

    @Transactional
    public void deletePack(Long projectId, Long packId) {
        requireProjectPack(projectId, packId);
        jdbcTemplate.update("DELETE FROM trace_rule_pack_config WHERE id = ?", packId);
    }

    private void updateStatus(Long projectId, Long packId, String status) {
        requireProjectPack(projectId, packId);
        jdbcTemplate.update("UPDATE trace_rule_pack_config SET status = ?, updated_at = ? WHERE id = ?",
                status, timeProvider.now(), packId);
    }

    private TraceRulePackConfigRecord requireProjectPack(Long projectId, Long packId) {
        TraceRulePackConfigRecord existing = getPack(packId);
        if (existing == null) {
            throw new BusinessException("规则包不存在");
        }
        if (existing.projectId() == null || !existing.projectId().equals(projectId)) {
            throw new BusinessException("只能修改当前项目的规则包");
        }
        return existing;
    }

    private String normalizeConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new BusinessException("规则包 JSON 不能为空");
        }
        try {
            objectMapper.readValue(configJson, TraceRuleSetConfig.class);
            return configJson.trim();
        } catch (Exception ex) {
            throw new BusinessException("规则包 JSON 格式不合法: " + ex.getMessage());
        }
    }

    private String normalizePackKey(String packKey) {
        if (packKey == null || packKey.isBlank()) {
            throw new BusinessException("规则包标识不能为空");
        }
        return packKey.trim().toUpperCase();
    }

    private String normalizeStatus(String status) {
        String normalized = normalizeWithDefault(status, "DRAFT");
        return switch (normalized) {
            case "DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED" -> normalized;
            default -> throw new BusinessException("不支持的规则包状态: " + status);
        };
    }

    private String normalizeWithDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TraceRulePackConfigRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new TraceRulePackConfigRecord(
                rs.getLong("id"),
                rs.getObject("project_id") == null ? null : rs.getLong("project_id"),
                rs.getString("pack_key"),
                rs.getString("pack_name"),
                rs.getString("pack_type"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getInt("priority"),
                rs.getString("config_json"),
                rs.getString("description"),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    public record TraceRulePackConfigRecord(
            Long id,
            Long projectId,
            String packKey,
            String packName,
            String packType,
            int version,
            String status,
            int priority,
            String configJson,
            String description,
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record CreateRulePackCommand(
            String packKey,
            String packName,
            String packType,
            String status,
            Integer priority,
            String configJson,
            String description
    ) {
    }

    public record UpdateRulePackCommand(
            String packName,
            String packType,
            String status,
            Integer priority,
            String configJson,
            String description
    ) {
    }
}
