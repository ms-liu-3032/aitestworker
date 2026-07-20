package com.company.aitest.generation;

import java.time.LocalDateTime;
import java.util.Optional;

import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Stores actual node output for long-running async generation tasks.
 * A checkpoint is deliberately transport-agnostic: it contains only the model output
 * for one pipeline node, never a business-specific fallback result.
 */
@Service
public class GenerationTaskCheckpointService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public GenerationTaskCheckpointService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public Optional<String> loadSucceededPayload(Long taskId, String stageKey) {
        if (taskId == null || stageKey == null || stageKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT payload FROM generation_task_stage_checkpoint
                        WHERE task_id = :taskId AND stage_key = :stageKey AND status = 'SUCCEEDED'
                        LIMIT 1
                        """)
                .param("taskId", taskId)
                .param("stageKey", stageKey)
                .query(String.class)
                .optional();
    }

    public void markRunning(Long taskId, String stageKey) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                        INSERT INTO generation_task_stage_checkpoint
                            (task_id, stage_key, status, attempt_count, created_at, updated_at)
                        VALUES (?, ?, 'RUNNING', 1, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            status = 'RUNNING', error_code = NULL, error_message = NULL,
                            attempt_count = attempt_count + 1, updated_at = VALUES(updated_at)
                        """, taskId, stageKey, now, now);
    }

    public void markSucceeded(Long taskId, String stageKey, String payload) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                        UPDATE generation_task_stage_checkpoint
                        SET status = 'SUCCEEDED', payload = ?, error_code = NULL, error_message = NULL,
                            completed_at = ?, updated_at = ?
                        WHERE task_id = ? AND stage_key = ?
                        """, payload, now, now, taskId, stageKey);
    }

    public void markFailed(Long taskId, String stageKey, String errorCode, String errorMessage) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                        UPDATE generation_task_stage_checkpoint
                        SET status = 'FAILED', error_code = ?, error_message = ?, updated_at = ?
                        WHERE task_id = ? AND stage_key = ?
                        """, errorCode, truncate(errorMessage, 2000), now, taskId, stageKey);
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
