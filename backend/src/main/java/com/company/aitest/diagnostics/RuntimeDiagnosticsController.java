package com.company.aitest.diagnostics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.llm.gateway.guard.SensitiveDataMasker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/runtime-diagnostics")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class RuntimeDiagnosticsController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1000;
    private static final int RAW_OUTPUT_PREVIEW_LIMIT = 500;
    private static final int SECURITY_DETAIL_PREVIEW_LIMIT = 800;

    private final JdbcClient jdbc;
    private final SensitiveDataMasker sensitiveDataMasker;

    public RuntimeDiagnosticsController(JdbcClient jdbc) {
        this(jdbc, new SensitiveDataMasker());
    }

    @Autowired
    public RuntimeDiagnosticsController(JdbcClient jdbc, SensitiveDataMasker sensitiveDataMasker) {
        this.jdbc = jdbc;
        this.sensitiveDataMasker = sensitiveDataMasker == null ? new SensitiveDataMasker() : sensitiveDataMasker;
    }

    @GetMapping("/llm-invocations/chain")
    public ApiResponse<LlmInvocationChainView> getLlmInvocationChain(@RequestParam String requestId) {
        String rootRequestId = rootRequestId(requestId);
        Map<String, Object> params = new HashMap<>();
        params.put("rootRequestId", rootRequestId);
        params.put("attemptPrefix", rootRequestId + "#%");
        List<LlmInvocationView> entries = jdbc.sql("""
                select id, request_id, user_id, project_id, task_id, task_type, stage,
                       model_config_id, provider, model_name, retry_index,
                       status, error_code, error_message, duration_ms,
                       token_input, token_cached_input, token_output, raw_output_snapshot, created_at
                from llm_invocation_log
                where request_id = :rootRequestId
                   or request_id like :attemptPrefix
                order by created_at asc, id asc
                """)
                .params(params)
                .query(this::mapLlmInvocation)
                .list();
        return ApiResponse.ok(new LlmInvocationChainView(rootRequestId, entries));
    }

    @GetMapping("/llm-invocations")
    public ApiResponse<List<LlmInvocationView>> listLlmInvocations(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queryLlmInvocations(projectId, taskId, status, errorCode, keyword, limit));
    }

    @GetMapping("/llm-invocations/snapshot")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LlmInvocationSnapshotView> getLlmInvocationSnapshot(@RequestParam Long id) {
        LlmInvocationSnapshotView snapshot = jdbc.sql("""
                select id, request_id, user_id, project_id, task_id, stage, provider, model_name,
                       status, error_code, error_message, raw_output_snapshot, created_at
                from llm_invocation_log
                where id = :id
                """)
                .param("id", id)
                .query(this::mapLlmInvocationSnapshot)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("LLM 调用日志不存在"));
        return ApiResponse.ok(snapshot);
    }

    @GetMapping(value = "/llm-invocations/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportLlmInvocations(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        List<LlmInvocationView> entries = queryLlmInvocations(projectId, taskId, status, errorCode, keyword, limit);
        String report = buildLlmInvocationReport(entries, normalizeLimit(limit));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"llm-diagnostics-report.md\"")
                .body(report);
    }

    @GetMapping("/security-events")
    public ApiResponse<List<SecurityEventView>> listSecurityEvents(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(querySecurityEvents(projectId, eventType, severity, keyword, limit));
    }

    @GetMapping(value = "/security-events/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportSecurityEvents(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        List<SecurityEventView> entries = querySecurityEvents(projectId, eventType, severity, keyword, limit);
        String report = buildSecurityEventReport(entries, normalizeLimit(limit));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"security-events-report.md\"")
                .body(report);
    }

    int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    String buildLlmInvocationReport(List<LlmInvocationView> entries, int limit) {
        Map<String, Long> byStatus = entries.stream()
                .collect(Collectors.groupingBy(v -> emptyDash(v.status()), Collectors.counting()));
        Map<String, Long> byError = entries.stream()
                .filter(v -> hasText(v.errorCode()))
                .collect(Collectors.groupingBy(v -> v.errorCode(), Collectors.counting()));
        long totalDuration = entries.stream()
                .map(LlmInvocationView::durationMs)
                .filter(v -> v != null && v > 0)
                .mapToLong(Long::longValue)
                .sum();
        long durationCount = entries.stream()
                .map(LlmInvocationView::durationMs)
                .filter(v -> v != null && v > 0)
                .count();
        long totalInput = entries.stream().mapToLong(v -> v.tokenInput() == null ? 0 : v.tokenInput()).sum();
        long totalCachedInput = entries.stream().mapToLong(v -> v.tokenCachedInput() == null ? 0 : v.tokenCachedInput()).sum();
        long totalOutput = entries.stream().mapToLong(v -> v.tokenOutput() == null ? 0 : v.tokenOutput()).sum();

        StringBuilder out = new StringBuilder();
        out.append("# LLM 运行诊断脱敏报告\n\n");
        out.append("- 生成时间：").append(LocalDateTime.now()).append('\n');
        out.append("- 导出范围：最近 ").append(limit).append(" 条匹配记录\n");
        out.append("- 记录数：").append(entries.size()).append('\n');
        out.append("- 平均耗时：").append(durationCount == 0 ? "-" : totalDuration / durationCount + " ms").append('\n');
        out.append("- Token 合计：input=").append(totalInput)
                .append(", cached_input=").append(totalCachedInput)
                .append(", uncached_input=").append(Math.max(0, totalInput - totalCachedInput))
                .append(", output=").append(totalOutput).append("\n");
        out.append("- 输入缓存命中率：")
                .append(totalInput == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f%%", totalCachedInput * 100.0 / totalInput))
                .append("\n\n");
        out.append("> 说明：本报告只包含数据库中的截断预览字段，不包含 API Key、完整 prompt、完整 raw output 或敏感凭证。\n\n");

        out.append("## 状态分布\n\n");
        if (byStatus.isEmpty()) {
            out.append("- 无\n\n");
        } else {
            byStatus.forEach((k, v) -> out.append("- ").append(k).append(": ").append(v).append('\n'));
            out.append('\n');
        }

        out.append("## 错误码分布\n\n");
        if (byError.isEmpty()) {
            out.append("- 无\n\n");
        } else {
            byError.forEach((k, v) -> out.append("- ").append(k).append(": ").append(v).append('\n'));
            out.append('\n');
        }

        out.append("## 明细\n\n");
        for (LlmInvocationView entry : entries) {
            out.append("### #").append(entry.id()).append(" ").append(emptyDash(entry.status())).append('\n');
            out.append("- 时间：").append(entry.createdAt() == null ? "-" : entry.createdAt()).append('\n');
            out.append("- requestId：").append(emptyDash(entry.requestId())).append('\n');
            out.append("- 项目/任务：").append(entry.projectId() == null ? "-" : entry.projectId())
                    .append(" / ").append(entry.taskId() == null ? "-" : entry.taskId()).append('\n');
            out.append("- 任务类型：").append(emptyDash(entry.taskType())).append('\n');
            out.append("- 阶段：").append(emptyDash(entry.stage())).append('\n');
            out.append("- 模型：").append(emptyDash(entry.provider())).append(" / ").append(emptyDash(entry.modelName())).append('\n');
            out.append("- retryIndex：").append(entry.retryIndex() == null ? "-" : entry.retryIndex()).append('\n');
            out.append("- 耗时：").append(entry.durationMs() == null ? "-" : entry.durationMs() + " ms").append('\n');
            out.append("- Token：input=").append(entry.tokenInput() == null ? 0 : entry.tokenInput())
                    .append(", cached_input=").append(entry.tokenCachedInput() == null ? 0 : entry.tokenCachedInput())
                    .append(", output=").append(entry.tokenOutput() == null ? 0 : entry.tokenOutput()).append('\n');
            out.append("- 错误码：").append(emptyDash(entry.errorCode())).append('\n');
            out.append("- 错误信息：").append(oneLine(entry.errorMessage())).append('\n');
            out.append("- 输出预览：").append(oneLine(entry.rawOutputPreview())).append("\n\n");
        }
        return out.toString();
    }

    String buildSecurityEventReport(List<SecurityEventView> entries, int limit) {
        Map<String, Long> bySeverity = entries.stream()
                .collect(Collectors.groupingBy(v -> emptyDash(v.severity()), Collectors.counting()));
        Map<String, Long> byType = entries.stream()
                .collect(Collectors.groupingBy(v -> emptyDash(v.eventType()), Collectors.counting()));

        StringBuilder out = new StringBuilder();
        out.append("# 安全事件脱敏报告\n\n");
        out.append("- 生成时间：").append(LocalDateTime.now()).append('\n');
        out.append("- 导出范围：最近 ").append(limit).append(" 条匹配记录\n");
        out.append("- 记录数：").append(entries.size()).append("\n\n");
        out.append("> 说明：本报告只包含安全事件截断预览，不包含完整 prompt、完整 raw output、API Key 或敏感凭证。\n\n");

        out.append("## 级别分布\n\n");
        if (bySeverity.isEmpty()) {
            out.append("- 无\n\n");
        } else {
            bySeverity.forEach((k, v) -> out.append("- ").append(k).append(": ").append(v).append('\n'));
            out.append('\n');
        }

        out.append("## 事件类型分布\n\n");
        if (byType.isEmpty()) {
            out.append("- 无\n\n");
        } else {
            byType.forEach((k, v) -> out.append("- ").append(k).append(": ").append(v).append('\n'));
            out.append('\n');
        }

        out.append("## 明细\n\n");
        for (SecurityEventView entry : entries) {
            out.append("### #").append(entry.id()).append(" ").append(emptyDash(entry.eventType())).append('\n');
            out.append("- 时间：").append(entry.createdAt() == null ? "-" : entry.createdAt()).append('\n');
            out.append("- 级别：").append(emptyDash(entry.severity())).append('\n');
            out.append("- requestId：").append(emptyDash(entry.requestId())).append('\n');
            out.append("- 用户/项目/任务：").append(entry.userId() == null ? "-" : entry.userId())
                    .append(" / ").append(entry.projectId() == null ? "-" : entry.projectId())
                    .append(" / ").append(entry.taskId() == null ? "-" : entry.taskId()).append('\n');
            out.append("- 详情预览：").append(oneLine(entry.detailPreview())).append("\n\n");
        }
        return out.toString();
    }

    String preview(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    String rootRequestId(String requestId) {
        if (requestId == null) {
            return "";
        }
        String trimmed = requestId.trim();
        int attemptSuffix = trimmed.indexOf("#a");
        if (attemptSuffix > 0) {
            return trimmed.substring(0, attemptSuffix);
        }
        return trimmed;
    }

    String maskSnapshot(String value) {
        return sensitiveDataMasker.mask(value);
    }

    private List<LlmInvocationView> queryLlmInvocations(Long projectId,
                                                        Long taskId,
                                                        String status,
                                                        String errorCode,
                                                        String keyword,
                                                        Integer limit) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("""
                select id, request_id, user_id, project_id, task_id, task_type, stage,
                       model_config_id, provider, model_name, retry_index,
                       status, error_code, error_message, duration_ms,
                       token_input, token_cached_input, token_output, raw_output_snapshot, created_at
                from llm_invocation_log
                where 1 = 1
                """);
        if (projectId != null) {
            sql.append(" and project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (taskId != null) {
            sql.append(" and task_id = :taskId");
            params.put("taskId", taskId);
        }
        if (hasText(status)) {
            sql.append(" and status = :status");
            params.put("status", status.trim());
        }
        if (hasText(errorCode)) {
            sql.append(" and error_code = :errorCode");
            params.put("errorCode", errorCode.trim());
        }
        if (hasText(keyword)) {
            sql.append("""
                     and (
                       request_id like :keyword
                       or stage like :keyword
                       or model_name like :keyword
                       or error_message like :keyword
                     )
                    """);
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" order by created_at desc, id desc limit :limit");
        params.put("limit", normalizeLimit(limit));
        return jdbc.sql(sql.toString()).params(params).query(this::mapLlmInvocation).list();
    }

    private List<SecurityEventView> querySecurityEvents(Long projectId,
                                                        String eventType,
                                                        String severity,
                                                        String keyword,
                                                        Integer limit) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("""
                select id, event_type, severity, user_id, project_id, task_id, request_id, detail_json, created_at
                from security_event_log
                where 1 = 1
                """);
        if (projectId != null) {
            sql.append(" and project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (hasText(eventType)) {
            sql.append(" and event_type = :eventType");
            params.put("eventType", eventType.trim());
        }
        if (hasText(severity)) {
            sql.append(" and severity = :severity");
            params.put("severity", severity.trim());
        }
        if (hasText(keyword)) {
            sql.append("""
                     and (
                       request_id like :keyword
                       or event_type like :keyword
                       or detail_json like :keyword
                     )
                    """);
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        sql.append(" order by created_at desc, id desc limit :limit");
        params.put("limit", normalizeLimit(limit));
        return jdbc.sql(sql.toString()).params(params).query(this::mapSecurityEvent).list();
    }

    private LlmInvocationView mapLlmInvocation(ResultSet rs, int rowNum) throws SQLException {
        return new LlmInvocationView(
                rs.getLong("id"),
                rs.getString("request_id"),
                getLongNullable(rs, "user_id"),
                getLongNullable(rs, "project_id"),
                getLongNullable(rs, "task_id"),
                rs.getString("task_type"),
                rs.getString("stage"),
                getLongNullable(rs, "model_config_id"),
                rs.getString("provider"),
                rs.getString("model_name"),
                getIntNullable(rs, "retry_index"),
                rs.getString("status"),
                rs.getString("error_code"),
                preview(rs.getString("error_message"), SECURITY_DETAIL_PREVIEW_LIMIT),
                getLongNullable(rs, "duration_ms"),
                rs.getInt("token_input"),
                rs.getInt("token_cached_input"),
                rs.getInt("token_output"),
                preview(rs.getString("raw_output_snapshot"), RAW_OUTPUT_PREVIEW_LIMIT),
                timestamp(rs, "created_at")
        );
    }

    private SecurityEventView mapSecurityEvent(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityEventView(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("severity"),
                getLongNullable(rs, "user_id"),
                getLongNullable(rs, "project_id"),
                getLongNullable(rs, "task_id"),
                rs.getString("request_id"),
                preview(rs.getString("detail_json"), SECURITY_DETAIL_PREVIEW_LIMIT),
                timestamp(rs, "created_at")
        );
    }

    private LlmInvocationSnapshotView mapLlmInvocationSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new LlmInvocationSnapshotView(
                rs.getLong("id"),
                rs.getString("request_id"),
                getLongNullable(rs, "user_id"),
                getLongNullable(rs, "project_id"),
                getLongNullable(rs, "task_id"),
                rs.getString("stage"),
                rs.getString("provider"),
                rs.getString("model_name"),
                rs.getString("status"),
                rs.getString("error_code"),
                maskSnapshot(rs.getString("error_message")),
                maskSnapshot(rs.getString("raw_output_snapshot")),
                timestamp(rs, "created_at")
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String emptyDash(String value) {
        return hasText(value) ? value.trim() : "-";
    }

    private String oneLine(String value) {
        if (!hasText(value)) {
            return "-";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private Long getLongNullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer getIntNullable(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record LlmInvocationView(
            Long id,
            String requestId,
            Long userId,
            Long projectId,
            Long taskId,
            String taskType,
            String stage,
            Long modelConfigId,
            String provider,
            String modelName,
            Integer retryIndex,
            String status,
            String errorCode,
            String errorMessage,
            Long durationMs,
            Integer tokenInput,
            Integer tokenCachedInput,
            Integer tokenOutput,
            String rawOutputPreview,
            LocalDateTime createdAt) {
    }

    public record LlmInvocationChainView(String rootRequestId, List<LlmInvocationView> entries) {
    }

    public record LlmInvocationSnapshotView(
            Long id,
            String requestId,
            Long userId,
            Long projectId,
            Long taskId,
            String stage,
            String provider,
            String modelName,
            String status,
            String errorCode,
            String errorMessage,
            String rawOutput,
            LocalDateTime createdAt) {
    }

    public record SecurityEventView(
            Long id,
            String eventType,
            String severity,
            Long userId,
            Long projectId,
            Long taskId,
            String requestId,
            String detailPreview,
            LocalDateTime createdAt) {
    }
}
