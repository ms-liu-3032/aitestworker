package com.company.aitest.skill;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SkillExecutionLogService {
    private final JdbcTemplate jdbcTemplate;

    public SkillExecutionLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long taskId, Long projectId, String skillName, SkillStage stage, String inputSnapshot,
                       String outputSnapshot, Long modelConfigId, String promptSnapshot, String allowedToolsJson,
                       String status, String errorCode, String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt,
                       long durationMs) {
        jdbcTemplate.update("""
                insert into skill_execution_log(task_id, project_id, skill_name, stage, input_snapshot, output_snapshot,
                  model_config_id, prompt_snapshot, allowed_tools_json, status, error_code, error_message,
                  started_at, finished_at, duration_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, taskId, projectId, skillName, stage.name(), inputSnapshot, outputSnapshot, modelConfigId,
                promptSnapshot, allowedToolsJson, status, errorCode, errorMessage, startedAt, finishedAt, durationMs,
                LocalDateTime.now());
    }
}
