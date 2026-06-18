package com.company.aitest.scan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 扫描源配置服务。
 * <p>
 * 支持全局和项目级扫描源配置，不再依赖 Java 接口。
 */
@Service
public class ScanSourceConfigService {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public ScanSourceConfigService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    /**
     * 列出管理页可见扫描源：项目级全部 + 全局已启用，项目级配置优先覆盖全局。
     */
    public List<ScanSourceConfigRecord> listSources(Long projectId) {
        // 项目级扫描源
        List<ScanSourceConfigRecord> projectSources = jdbc.sql("""
                SELECT * FROM scan_source_config
                WHERE project_id = ?
                ORDER BY enabled DESC, default_selected DESC, source_key ASC
                """).param(projectId).query(this::mapRecord).list();

        // 全局扫描源（排除已被项目级覆盖的）
        List<ScanSourceConfigRecord> globalSources = jdbc.sql("""
                SELECT * FROM scan_source_config
                WHERE project_id IS NULL AND enabled = 1
                  AND source_key NOT IN (
                      SELECT source_key FROM scan_source_config
                      WHERE project_id = ?
                  )
                ORDER BY default_selected DESC, source_key ASC
                """).param(projectId).query(this::mapRecord).list();

        var result = new java.util.ArrayList<ScanSourceConfigRecord>();
        result.addAll(projectSources);
        result.addAll(globalSources);
        return result;
    }

    /**
     * 列出默认选中的扫描源。
     */
    public List<ScanSourceConfigRecord> listDefaultSources(Long projectId) {
        return listEnabledSources(projectId).stream()
                .filter(ScanSourceConfigRecord::defaultSelected)
                .toList();
    }

    public ScanSourceConfigRecord getSource(Long id) {
        List<ScanSourceConfigRecord> results = jdbc.sql("SELECT * FROM scan_source_config WHERE id = :id")
                .param("id", id).query(this::mapRecord).list();
        return results.isEmpty() ? null : results.get(0);
    }

    public ScanSourceConfigRecord getSource(Long projectId, Long id) {
        ScanSourceConfigRecord existing = getSource(id);
        if (existing == null) {
            throw new BusinessException("扫描源配置不存在");
        }
        if (existing.projectId() != null && !existing.projectId().equals(projectId)) {
            throw new BusinessException("扫描源不属于当前项目");
        }
        return existing;
    }

    @Transactional
    public ScanSourceConfigRecord createSource(CreateSourceCommand cmd, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String sourceKey = normalizeSourceKey(cmd.sourceKey());
        String sourceLabel = normalizeSourceLabel(cmd.sourceLabel());
        String sourceType = normalizeSourceType(cmd.sourceType());
        jdbcTemplate.update("""
                INSERT INTO scan_source_config(project_id, source_key, source_label, source_type,
                    source_url, source_file_path, default_selected, enabled, description,
                    config_json, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    id = LAST_INSERT_ID(id),
                    source_label = VALUES(source_label),
                    source_type = VALUES(source_type),
                    source_url = VALUES(source_url),
                    source_file_path = VALUES(source_file_path),
                    default_selected = VALUES(default_selected),
                    description = VALUES(description),
                    config_json = VALUES(config_json),
                    updated_at = VALUES(updated_at)
                """, cmd.projectId(), sourceKey, sourceLabel, sourceType,
                blankToNull(cmd.sourceUrl()), blankToNull(cmd.sourceFilePath()),
                cmd.defaultSelected() ? 1 : 0, cmd.enabled() == null || cmd.enabled() ? 1 : 0,
                blankToNull(cmd.description()), blankToNull(cmd.configJson()),
                user != null ? user.id() : null, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getSource(id);
    }

    @Transactional
    public ScanSourceConfigRecord updateSource(Long projectId, Long id, UpdateSourceCommand cmd, CurrentUser user) {
        requireProjectSource(projectId, id);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE scan_source_config SET
                    source_label = COALESCE(?, source_label),
                    source_type = COALESCE(?, source_type),
                    source_url = COALESCE(?, source_url),
                    source_file_path = COALESCE(?, source_file_path),
                    default_selected = COALESCE(?, default_selected),
                    enabled = COALESCE(?, enabled),
                    description = COALESCE(?, description),
                    config_json = COALESCE(?, config_json),
                    updated_at = ?
                WHERE id = ?
                """, blankToNull(cmd.sourceLabel()), cmd.sourceType() == null ? null : normalizeSourceType(cmd.sourceType()),
                blankToNull(cmd.sourceUrl()), blankToNull(cmd.sourceFilePath()),
                cmd.defaultSelected() != null ? (cmd.defaultSelected() ? 1 : 0) : null,
                cmd.enabled() != null ? (cmd.enabled() ? 1 : 0) : null,
                blankToNull(cmd.description()), blankToNull(cmd.configJson()), now, id);
        return getSource(id);
    }

    @Transactional
    public void deleteSource(Long projectId, Long id) {
        requireProjectSource(projectId, id);
        jdbcTemplate.update("DELETE FROM scan_source_config WHERE id = ?", id);
    }

    @Transactional
    public void enableSource(Long projectId, Long id) {
        requireProjectSource(projectId, id);
        jdbcTemplate.update("UPDATE scan_source_config SET enabled = 1, updated_at = NOW() WHERE id = ?", id);
    }

    @Transactional
    public void disableSource(Long projectId, Long id) {
        requireProjectSource(projectId, id);
        jdbcTemplate.update("UPDATE scan_source_config SET enabled = 0, updated_at = NOW() WHERE id = ?", id);
    }

    /**
     * 将数据库配置转换为 BuiltinScanSourceDefinition，供 ControlledScanService 使用。
     */
    public List<BuiltinScanSourceDefinition> toBuiltinDefinitions(Long projectId) {
        return listEnabledSources(projectId).stream()
                .map(record -> new BuiltinScanSourceDefinition(
                        record.sourceKey(),
                        record.sourceLabel(),
                        record.sourceFilePath() != null ? java.nio.file.Path.of(record.sourceFilePath()) : null,
                        record.defaultSelected(),
                        record.sourceType(),
                        record.sourceUrl()
                ))
                .toList();
    }

    private List<ScanSourceConfigRecord> listEnabledSources(Long projectId) {
        return listSources(projectId).stream()
                .filter(ScanSourceConfigRecord::enabled)
                .toList();
    }

    private ScanSourceConfigRecord requireProjectSource(Long projectId, Long id) {
        ScanSourceConfigRecord existing = getSource(id);
        if (existing == null) {
            throw new BusinessException("扫描源配置不存在");
        }
        if (existing.projectId() == null || !existing.projectId().equals(projectId)) {
            throw new BusinessException("只能修改当前项目的扫描源");
        }
        return existing;
    }

    private String normalizeSourceKey(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new BusinessException("扫描源标识不能为空");
        }
        return sourceKey.trim().toUpperCase();
    }

    private String normalizeSourceType(String sourceType) {
        return sourceType == null || sourceType.isBlank()
                ? "URL_LIST"
                : sourceType.trim().toUpperCase();
    }

    private String normalizeSourceLabel(String sourceLabel) {
        if (sourceLabel == null || sourceLabel.isBlank()) {
            throw new BusinessException("扫描源名称不能为空");
        }
        return sourceLabel.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ScanSourceConfigRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new ScanSourceConfigRecord(
                rs.getLong("id"),
                rs.getObject("project_id") == null ? null : rs.getLong("project_id"),
                rs.getString("source_key"),
                rs.getString("source_label"),
                rs.getString("source_type"),
                rs.getString("source_url"),
                rs.getString("source_file_path"),
                rs.getInt("default_selected") == 1,
                rs.getInt("enabled") == 1,
                rs.getString("description"),
                rs.getString("config_json"),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null
        );
    }

    public record ScanSourceConfigRecord(
            Long id, Long projectId, String sourceKey, String sourceLabel,
            String sourceType, String sourceUrl, String sourceFilePath,
            boolean defaultSelected, boolean enabled, String description,
            String configJson, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record CreateSourceCommand(
            Long projectId, String sourceKey, String sourceLabel, String sourceType,
            String sourceUrl, String sourceFilePath, boolean defaultSelected,
            Boolean enabled, String description, String configJson
    ) {}

    public record UpdateSourceCommand(
            String sourceLabel, String sourceType, String sourceUrl, String sourceFilePath,
            Boolean defaultSelected, Boolean enabled, String description, String configJson
    ) {}
}
