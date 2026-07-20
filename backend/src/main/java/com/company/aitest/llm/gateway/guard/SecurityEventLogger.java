package com.company.aitest.llm.gateway.guard;

import java.time.LocalDateTime;

import com.company.aitest.common.TimeProvider;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * security_event_log 写入器。失败不影响主链路。
 */
@Component
public class SecurityEventLogger {
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public SecurityEventLogger(JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public void record(String eventType, String severity, LlmInvocationRequest request, String requestId, String detailJson) {
        try {
            LocalDateTime now = timeProvider.now();
            jdbcTemplate.update("""
                    insert into security_event_log(event_type, severity, user_id, project_id, task_id, request_id, detail_json, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    eventType,
                    severity,
                    request == null ? null : request.userId(),
                    request == null ? null : request.projectId(),
                    request == null ? null : request.taskId(),
                    requestId,
                    detailJson,
                    now);
        } catch (RuntimeException ex) {
            System.err.println("[SecurityEventLogger] failed to record: " + ex.getMessage());
        }
    }
}
