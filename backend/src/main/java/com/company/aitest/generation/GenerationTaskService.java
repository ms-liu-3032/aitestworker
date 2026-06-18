package com.company.aitest.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

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
                  generation_mode, use_mini_tom, model_config_id, prompt_snapshot, created_by, created_at, updated_at)
                values (?, ?, ?, 'CREATED', 'CREATED', ?, ?, ?, ?, ?, ?, ?)
                """, projectId, command.taskName(), command.requirementText(),
                mode, useTom ? 1 : 0, command.modelConfigId(),
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
                rs.getString("status"), rs.getLong("model_config_id"), rs.getString("prompt_snapshot"),
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

    public record CreateTaskCommand(String taskName, String requirementText, Long modelConfigId, String promptSnapshot,
                                    String generationMode, Boolean useMiniTom) {
    }
}
