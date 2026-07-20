package com.company.aitest.llm.gateway.audit;

import java.time.LocalDateTime;

import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 写 llm_invocation_log。
 * <p>
 * 任何路径调用 LLM 都会经过这里产生一条记录；包括失败、被守卫拦截、限流拒绝。
 * 不允许 update / delete，只允许 insert（审计完整性）。
 */
@Component
public class LlmInvocationLogger {

    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public LlmInvocationLogger(JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    /**
     * @return 新插入记录的 id；任何异常都会被吞掉并打日志，避免审计失败连带业务失败。
     */
    public Long record(LlmInvocationLogEntry entry) {
        LocalDateTime now = timeProvider.now();
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        insert into llm_invocation_log(
                          request_id, user_id, project_id, task_id, task_type, stage,
                          model_config_id, provider, model_name, retry_index,
                          prompt_template_id, prompt_version, prompt_snapshot_id,
                          input_snapshot_ref, output_snapshot_ref, raw_output_snapshot, context_manifest_id,
                          token_input, token_cached_input, token_output, status, error_code, error_message,
                          duration_ms, created_at)
                        values (?, ?, ?, ?, ?, ?, ?,
                          (select provider from model_config where id = ?),
                          (select model_name from model_config where id = ?),
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                int i = 1;
                ps.setString(i++, truncate(entry.requestId(), 64));
                setLong(ps, i++, entry.userId());
                setLong(ps, i++, entry.projectId());
                setLong(ps, i++, entry.taskId());
                ps.setString(i++, truncate(entry.taskType(), 128));
                ps.setString(i++, truncate(entry.stage(), 64));
                setLong(ps, i++, entry.modelConfigId());
                setLong(ps, i++, entry.modelConfigId());
                setLong(ps, i++, entry.modelConfigId());
                setInt(ps, i++, entry.retryIndex());
                setLong(ps, i++, entry.promptTemplateId());
                setInt(ps, i++, entry.promptVersion());
                setLong(ps, i++, entry.promptSnapshotId());
                ps.setString(i++, truncate(entry.inputSnapshotRef(), 512));
                ps.setString(i++, truncate(entry.outputSnapshotRef(), 512));
                ps.setString(i++, entry.rawOutputSnapshot());
                setLong(ps, i++, entry.contextManifestId());
                ps.setInt(i++, entry.tokenInput());
                ps.setInt(i++, entry.tokenCachedInput());
                ps.setInt(i++, entry.tokenOutput());
                ps.setString(i++, truncate(entry.status(), 32));
                ps.setString(i++, truncate(entry.errorCode(), 64));
                ps.setString(i++, entry.errorMessage());
                ps.setLong(i++, entry.durationMs());
                ps.setObject(i, now);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (RuntimeException ex) {
            // 审计写入失败不应该带垮业务。打到 stderr，等待运维补排查；
            // 实际监控由 Loki/Prometheus 接，本方案先简化。
            System.err.println("[LlmInvocationLogger] failed to record: " + ex.getMessage());
            return null;
        }
    }

    private static void setLong(PreparedStatement ps, int idx, Long v) throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.BIGINT);
        else ps.setLong(idx, v);
    }

    private static void setInt(PreparedStatement ps, int idx, Integer v) throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER);
        else ps.setInt(idx, v);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
