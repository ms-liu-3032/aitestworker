package com.company.aitest.generation.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.common.TomUsageMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class GenerationSessionService {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public GenerationSessionService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public GenerationSessionRecord create(Long projectId, CreateSessionCommand cmd, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        TomUsageMode tomMode = parseTomMode(cmd.tomMode(), cmd.useMiniTom());
        jdbcTemplate.update("""
                INSERT INTO generation_session(project_id, session_title, status, model_config_id, prompt_template_id, use_mini_tom, tom_mode, created_by, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                """, projectId, cmd.sessionTitle(), cmd.modelConfigId(), cmd.promptTemplateId(),
                tomMode.usesTom() ? 1 : 0, tomMode.name(), user.id(), now, now);
        Long id = jdbc.sql("SELECT last_insert_id()").query(Long.class).single();
        return get(projectId, id, user);
    }

    public PageResult<GenerationSessionRecord> list(Long projectId, int page, int size, String status, String keyword, CurrentUser user) {
        StringBuilder where = new StringBuilder("WHERE project_id = :projectId AND created_by = :createdBy");
        var params = new java.util.HashMap<String, Object>();
        params.put("projectId", projectId);
        params.put("createdBy", user.id());

        if (status != null && !status.isBlank()) {
            where.append(" AND status = :status");
            params.put("status", status);
        } else {
            where.append(" AND status <> 'ARCHIVED'");
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND session_title LIKE :keyword");
            params.put("keyword", "%" + keyword + "%");
        }

        int total = jdbc.sql("SELECT COUNT(*) FROM generation_session " + where).params(params).query(Integer.class).single();
        int offset = (page - 1) * size;
        List<GenerationSessionRecord> items = jdbc.sql(
                        "SELECT * FROM generation_session " + where + " ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", size).param("offset", offset).params(params).query(this::map).list();
        return new PageResult<>(items, total, page, size);
    }

    public GenerationSessionRecord get(Long projectId, Long sessionId, CurrentUser user) {
        String sql = projectId != null
                ? "SELECT * FROM generation_session WHERE id = :id AND project_id = :pid AND created_by = :uid"
                : "SELECT * FROM generation_session WHERE id = :id AND created_by = :uid";
        var query = jdbc.sql(sql).param("id", sessionId);
        if (projectId != null) query = query.param("pid", projectId);
        query = query.param("uid", user.id());
        var list = query.query(this::map).list();
        if (list.isEmpty()) throw new BusinessException("会话不存在");
        return list.get(0);
    }

    public void archive(Long projectId, Long sessionId, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE generation_session SET status = 'ARCHIVED', updated_at = ? WHERE id = ? AND project_id = ? AND created_by = ?",
                now, sessionId, projectId, user.id());
    }

    public void updateTitle(Long projectId, Long sessionId, String title, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE generation_session SET session_title = ?, updated_at = ? WHERE id = ? AND project_id = ? AND created_by = ?",
                title, now, sessionId, projectId, user.id());
    }

    public void updateConfig(Long projectId, Long sessionId, Long modelConfigId, Long promptTemplateId, boolean useMiniTom, CurrentUser user) {
        updateConfig(projectId, sessionId, modelConfigId, promptTemplateId,
                TomUsageMode.resolve(null, useMiniTom).name(), user);
    }

    public void updateConfig(Long projectId, Long sessionId, Long modelConfigId, Long promptTemplateId,
                             String tomModeValue, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        TomUsageMode tomMode = parseTomMode(tomModeValue, false);
        jdbcTemplate.update("UPDATE generation_session SET model_config_id = ?, prompt_template_id = ?, use_mini_tom = ?, tom_mode = ?, updated_at = ? WHERE id = ? AND project_id = ? AND created_by = ?",
                modelConfigId, promptTemplateId, tomMode.usesTom() ? 1 : 0, tomMode.name(),
                now, sessionId, projectId, user.id());
    }

    private TomUsageMode parseTomMode(String tomModeValue, boolean legacyUseMiniTom) {
        if (tomModeValue == null || tomModeValue.isBlank()) {
            return TomUsageMode.resolve(null, legacyUseMiniTom);
        }
        try {
            return TomUsageMode.requireExplicit(tomModeValue);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("不支持的 TOM 使用模式: " + tomModeValue);
        }
    }

    public void updateStatus(Long sessionId, String status) {
        String normalizedStatus = requireSessionStatus(status);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE generation_session SET status = ?, updated_at = ? WHERE id = ?", normalizedStatus, now, sessionId);
    }

    public void updateStage(Long sessionId, String stage) {
        String normalizedStage = requireSessionStage(stage);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE generation_session SET current_stage = ?, updated_at = ? WHERE id = ?", normalizedStage, now, sessionId);
    }

    static String requireSessionStatus(String status) {
        try {
            return GenerationSessionStatus.valueOf(status == null ? "" : status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("不支持的会话状态: " + status);
        }
    }

    static String requireSessionStage(String stage) {
        try {
            return GenerationSessionStage.valueOf(stage == null ? "" : stage.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("不支持的会话阶段: " + stage);
        }
    }

    public void updateLatestAnalysisVersion(Long sessionId, int version) {
        jdbcTemplate.update("UPDATE generation_session SET latest_analysis_version = ? WHERE id = ?", version, sessionId);
    }

    public int reserveNextAnalysisVersion(Long sessionId) {
        for (int i = 0; i < 5; i++) {
            Integer currentVersion = jdbcTemplate.queryForObject(
                    "SELECT latest_analysis_version FROM generation_session WHERE id = ?",
                    Integer.class,
                    sessionId
            );
            if (currentVersion == null) {
                throw new BusinessException("会话不存在");
            }
            int updated = jdbcTemplate.update(
                    "UPDATE generation_session SET latest_analysis_version = latest_analysis_version + 1 WHERE id = ? AND latest_analysis_version = ?",
                    sessionId,
                    currentVersion
            );
            if (updated == 1) {
                return currentVersion + 1;
            }
        }
        throw new BusinessException("会话正在处理中，请稍后再试");
    }

    public void updateExecutionTaskId(Long sessionId, Long taskId) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE generation_session SET execution_task_id = ?, updated_at = ? WHERE id = ?", taskId, now, sessionId);
    }

    public Optional<GenerationSessionRecord> findByExecutionTaskId(Long taskId) {
        return jdbc.sql("SELECT * FROM generation_session WHERE execution_task_id = :taskId LIMIT 1")
                .param("taskId", taskId)
                .query(this::map)
                .optional();
    }

    public void restoreInterruptedExecutionTasks(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        LocalDateTime now = timeProvider.now();
        for (Long taskId : taskIds) {
            jdbcTemplate.update("""
                    UPDATE generation_session
                    SET status = 'ACTIVE', updated_at = ?
                    WHERE execution_task_id = ? AND status = 'GENERATING'
                    """, now, taskId);
        }
    }

    private GenerationSessionRecord map(ResultSet rs, int rowNum) throws SQLException {
        long mcId = rs.getLong("model_config_id");
        Long modelConfigId = rs.wasNull() ? null : mcId;
        Long promptTemplateId = rs.getLong("prompt_template_id");
        if (rs.wasNull()) promptTemplateId = null;
        Long execTaskId = rs.getLong("execution_task_id");
        if (rs.wasNull()) execTaskId = null;
        return new GenerationSessionRecord(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("session_title"),
                rs.getString("status"), rs.getString("current_stage"),
                modelConfigId, promptTemplateId,
                rs.getString("prompt_snapshot"),
                rs.getInt("use_mini_tom") == 1, rs.getString("tom_mode"),
                rs.getInt("latest_analysis_version"),
                execTaskId, rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
