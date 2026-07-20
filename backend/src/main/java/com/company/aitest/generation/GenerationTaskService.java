package com.company.aitest.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.workflow.GenerationStateMachine;
import com.company.aitest.workflow.TaskStatus;
import com.company.aitest.workflow.WorkflowEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationTaskService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final GenerationStateMachine stateMachine;

    public GenerationTaskService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                 GenerationStateMachine stateMachine) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public GenerationTaskRecord create(Long projectId, CreateTaskCommand command, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String mode = command.generationMode() != null ? command.generationMode() : "DIRECT";
        boolean useTom = Boolean.TRUE.equals(command.useMiniTom());
        jdbcTemplate.update("""
                insert into generation_task(project_id, task_name, requirement_text, current_stage, status,
                  run_status, generation_mode, use_mini_tom, model_config_id, prompt_template_id, prompt_version,
                  prompt_snapshot, created_by, created_at, updated_at)
                values (?, ?, ?, 'CREATED', 'CREATED', 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, projectId, command.taskName(), command.requirementText(),
                mode, useTom ? 1 : 0, command.modelConfigId(),
                command.promptTemplateId(), command.promptVersion(),
                command.promptSnapshot(), user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return get(projectId, id);
    }

    public List<GenerationTaskRecord> list(Long projectId) {
        return jdbc.sql("select * from generation_task where project_id = :projectId order by id desc")
                .param("projectId", projectId)
                .query(this::map)
                .list();
    }

    public GenerationTaskRecord get(Long projectId, Long taskId) {
        return jdbc.sql("select * from generation_task where project_id = :projectId and id = :taskId")
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query(this::map)
                .single();
    }

    public Optional<GenerationTaskRecord> findActiveByHash(Long projectId, String taskType, String requestHash) {
        return jdbc.sql("""
                select * from generation_task
                where project_id = :projectId
                  and task_type = :taskType
                  and request_hash = :requestHash
                  and run_status in ('PENDING', 'RUNNING')
                order by id desc
                limit 1
                """)
                .param("projectId", projectId)
                .param("taskType", taskType)
                .param("requestHash", requestHash)
                .query(this::map)
                .optional();
    }

    @Transactional
    public GenerationTaskRecord createAsync(Long projectId, CreateTaskCommand command, String taskType,
                                            String requestHash, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String mode = command.generationMode() != null ? command.generationMode() : "DIRECT";
        boolean useTom = Boolean.TRUE.equals(command.useMiniTom());
        jdbcTemplate.update("""
                insert into generation_task(project_id, task_name, requirement_text, current_stage, status,
                  run_status, request_hash, task_type, generation_mode, use_mini_tom, model_config_id,
                  prompt_template_id, prompt_version, prompt_snapshot, created_by, created_at, updated_at)
                values (?, ?, ?, 'CREATED', 'CREATED', 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, projectId, command.taskName(), command.requirementText(),
                requestHash, taskType, mode, useTom ? 1 : 0, command.modelConfigId(),
                command.promptTemplateId(), command.promptVersion(), command.promptSnapshot(), user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return get(projectId, id);
    }

    public int markRunning(Long taskId) {
        LocalDateTime now = timeProvider.now();
        return jdbcTemplate.update("""
                update generation_task
                set run_status = 'RUNNING', error_code = null, error_message = null,
                    started_at = ?, finished_at = null, updated_at = ?
                where id = ? and run_status in ('PENDING', 'FAILED', 'TIMEOUT')
                """, now, now, taskId);
    }

    public void markSucceeded(Long taskId) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update generation_task
                set run_status = 'SUCCEEDED', error_code = null, error_message = null,
                    finished_at = ?, updated_at = ?
                where id = ? and run_status = 'RUNNING'
                """, now, now, taskId);
    }

    public void markFailed(Long taskId, String runStatus, String errorCode, String errorMessage) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update generation_task
                set run_status = ?, error_code = ?, error_message = ?,
                    finished_at = ?, updated_at = ?
                where id = ? and run_status in ('PENDING', 'RUNNING')
                """, normalizeRunStatus(runStatus), errorCode, truncate(errorMessage, 2000), now, now, taskId);
    }

    /**
     * Records durable task progress.  Async work is intentionally node-based, so a task that
     * keeps producing checkpoints or drafts must not be treated as hung merely because it has
     * been running for a long time.
     */
    public void touchProgress(Long taskId) {
        jdbcTemplate.update("""
                update generation_task
                set updated_at = ?
                where id = ? and run_status = 'RUNNING'
                """, timeProvider.now(), taskId);
    }

    /** Marks a task terminal only when it has made no durable progress since {@code idleBefore}. */
    public boolean markTimedOutIfIdle(Long taskId, LocalDateTime idleBefore, String message) {
        LocalDateTime now = timeProvider.now();
        return jdbcTemplate.update("""
                update generation_task
                set run_status = 'TIMEOUT', error_code = 'TIMEOUT', error_message = ?,
                    finished_at = ?, updated_at = ?
                where id = ? and run_status in ('PENDING', 'RUNNING')
                  and updated_at <= ?
                """, truncate(message, 2000), now, now, taskId, idleBefore) > 0;
    }

    /**
     * A separate, deliberately generous circuit breaker for genuinely runaway work.  Normal
     * long-running generation is governed by the progress heartbeat above, not this limit.
     */
    public boolean markTimedOutIfExceededMaxRuntime(Long taskId, LocalDateTime startedBefore, String message) {
        LocalDateTime now = timeProvider.now();
        return jdbcTemplate.update("""
                update generation_task
                set run_status = 'TIMEOUT', error_code = 'TIMEOUT', error_message = ?,
                    finished_at = ?, updated_at = ?
                where id = ? and run_status = 'RUNNING'
                  and started_at <= ?
                """, truncate(message, 2000), now, now, taskId, startedBefore) > 0;
    }

    public boolean cancel(Long projectId, Long taskId) {
        LocalDateTime now = timeProvider.now();
        return jdbcTemplate.update("""
                update generation_task
                set run_status = 'CANCELED', finished_at = ?, updated_at = ?
                where project_id = ? and id = ? and run_status in ('PENDING', 'RUNNING')
                """, now, now, projectId, taskId) > 0;
    }

    /**
     * The executor is in-process. On application restart its PENDING/RUNNING work cannot resume,
     * so these rows must become retryable instead of blocking the same request hash forever.
     */
    @Transactional
    public List<Long> failInterruptedTasks() {
        List<Long> taskIds = jdbc.sql("""
                select id from generation_task
                where run_status in ('PENDING', 'RUNNING')
                """)
                .query(Long.class)
                .list();
        if (taskIds.isEmpty()) {
            return taskIds;
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update generation_task
                set run_status = 'FAILED', error_code = 'APP_RESTARTED',
                    error_message = '应用重启导致后台任务中断，请从失败节点继续。',
                    finished_at = ?, updated_at = ?
                where run_status in ('PENDING', 'RUNNING')
                """, now, now);
        return taskIds;
    }

    public boolean resetForRetry(Long projectId, Long taskId) {
        LocalDateTime now = timeProvider.now();
        return jdbcTemplate.update("""
                update generation_task
                set run_status = 'PENDING', error_code = null, error_message = null,
                    retry_count = retry_count + 1, started_at = null, finished_at = null, updated_at = ?
                where project_id = ? and id = ? and run_status in ('FAILED', 'TIMEOUT')
                """, now, projectId, taskId) > 0;
    }

    public int draftCount(Long projectId, Long taskId) {
        return jdbc.sql("""
                select count(*) from test_case_draft
                where project_id = :projectId and task_id = :taskId
                """)
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query(Integer.class)
                .single();
    }

    public boolean isCanceled(Long taskId) {
        return jdbc.sql("select count(*) from generation_task where id = :taskId and run_status = 'CANCELED'")
                .param("taskId", taskId)
                .query(Integer.class)
                .single() > 0;
    }

    public String buildRequestHash(Long projectId, String taskType, String inputContent,
                                   Integer promptVersion, Long modelConfigId, Long createdBy) {
        return buildRequestHash(projectId, taskType, inputContent, promptVersion, modelConfigId, null, null, createdBy);
    }

    public String buildRequestHash(Long projectId, String taskType, String inputContent,
                                   Integer promptVersion, Long modelConfigId,
                                   String promptContentHash, String modelFingerprint, Long createdBy) {
        return buildRequestHash(projectId, taskType, inputContent, promptVersion, modelConfigId,
                promptContentHash, null, modelFingerprint, createdBy);
    }

    public String buildRequestHash(Long projectId, String taskType, String inputContent,
                                   Integer promptVersion, Long modelConfigId,
                                   String promptContentHash, String promptTemplateFingerprint,
                                   String modelFingerprint, Long createdBy) {
        String material = projectId + "|" + taskType + "|" + (inputContent == null ? "" : inputContent)
                + "|" + (promptVersion == null ? "" : promptVersion)
                + "|" + (modelConfigId == null ? "" : modelConfigId)
                + "|" + (promptContentHash == null ? "" : promptContentHash)
                + "|" + (promptTemplateFingerprint == null ? "" : promptTemplateFingerprint)
                + "|" + (modelFingerprint == null ? "" : modelFingerprint)
                + "|" + (createdBy == null ? "" : createdBy);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("生成请求指纹失败");
        }
    }

    @Transactional
    public GenerationTaskRecord transit(Long projectId, Long taskId, WorkflowEvent event, CurrentUser user) {
        GenerationTaskRecord task = get(projectId, taskId);
        TaskStatus next = stateMachine.transit(TaskStatus.valueOf(task.status()), event);
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update(
                "update generation_task set status = ?, current_stage = ?, updated_at = ? where id = ? and status = ?",
                next.name(), next.name(), now, taskId, task.status());
        if (affected == 0) {
            throw new BusinessException("该任务已被其他用户处理，请刷新后查看最新状态");
        }
        jdbcTemplate.update("""
                insert into task_status_log(task_type, task_id, from_status, to_status, event_name, created_by, created_at)
                values ('GENERATION_WORKFLOW', ?, ?, ?, ?, ?, ?)
                """, taskId, task.status(), next.name(), event.name(), user.id(), now);
        return get(projectId, taskId);
    }

    private GenerationTaskRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new GenerationTaskRecord(rs.getLong("id"), rs.getLong("project_id"), rs.getString("task_name"),
                rs.getString("requirement_text"), rs.getString("requirement_type"), rs.getString("current_stage"),
                rs.getString("status"), rs.getString("task_type"),
                rs.getString("run_status"), rs.getString("request_hash"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getInt("retry_count"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
                rs.getLong("model_config_id"), getNullableLong(rs, "prompt_template_id"),
                getNullableInt(rs, "prompt_version"), rs.getString("prompt_snapshot"),
                rs.getLong("created_by"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getString("generation_mode"),
                rs.getBoolean("use_mini_tom"),
                rs.getString("mini_tom_context_snapshot"),
                rs.getString("test_scope_snapshot"),
                rs.getInt("tom_hit_count"),
                rs.getString("project_tom_snapshot"),
                rs.getString("system_tom_snapshot"),
                rs.getString("tom_weight_config"),
                rs.getString("clarification_questions_snapshot"),
                rs.getString("clarification_answers_snapshot"),
                rs.getString("assumptions_snapshot"));
    }

    public record CreateTaskCommand(String taskName, String requirementText, Long modelConfigId,
                                    Long promptTemplateId, Integer promptVersion, String promptSnapshot,
                                    String generationMode, Boolean useMiniTom) {
    }

    private static Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int value = rs.getInt(col);
        return rs.wasNull() ? null : value;
    }

    private static Long getNullableLong(ResultSet rs, String col) throws SQLException {
        long value = rs.getLong(col);
        return rs.wasNull() ? null : value;
    }

    private String normalizeRunStatus(String runStatus) {
        if ("TIMEOUT".equals(runStatus)) {
            return "TIMEOUT";
        }
        return "FAILED";
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
