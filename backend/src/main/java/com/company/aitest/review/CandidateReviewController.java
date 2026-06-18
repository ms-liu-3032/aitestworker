package com.company.aitest.review;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.trace.TraceCorrectionSuggestionService;
import com.company.aitest.trace.TraceSummaryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 管理后台候选资产统一审核。
 */
@RestController
@RequestMapping("/api/admin/candidates")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class CandidateReviewController {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final MiniTomService miniTomService;
    private final TraceSummaryService traceSummaryService;
    private final TraceCorrectionSuggestionService correctionSuggestionService;

    public CandidateReviewController(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                     TimeProvider timeProvider, MiniTomService miniTomService,
                                     TraceSummaryService traceSummaryService,
                                     TraceCorrectionSuggestionService correctionSuggestionService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.miniTomService = miniTomService;
        this.traceSummaryService = traceSummaryService;
        this.correctionSuggestionService = correctionSuggestionService;
    }

    // =====================================================================
    // 统一候选列表
    // =====================================================================

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listCandidates(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long createdBy) {

        // 如果指定了类型，只查该类型
        if (type != null && !type.isBlank()) {
            return ApiResponse.ok(queryCandidates(type, projectId, status, createdBy));
        }

        // 否则查所有类型的候选
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        all.addAll(queryCandidates("tom", projectId, status, createdBy));
        all.addAll(queryCandidates("summary", projectId, status, createdBy));
        all.addAll(queryCandidates("correction", projectId, status, createdBy));
        all.addAll(queryCandidates("skill", projectId, status, createdBy));
        all.addAll(queryCandidates("tool", projectId, status, createdBy));
        return ApiResponse.ok(all);
    }

    private List<Map<String, Object>> queryCandidates(String type, Long projectId, String status, Long createdBy) {
        String tableName = switch (type) {
            case "tom" -> "test_object_model";
            case "summary" -> "trace_summary";
            case "correction" -> "trace_correction_candidate";
            case "skill" -> "test_skill_template";
            case "tool" -> "test_tool_template";
            default -> throw new BusinessException("不支持的候选类型: " + type);
        };

        StringBuilder sql = new StringBuilder("SELECT * FROM " + tableName + " WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        // 默认只查 CANDIDATE / DRAFT 状态
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        } else {
            sql.append(" AND status IN ('CANDIDATE', 'DRAFT')");
        }

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (createdBy != null) {
            sql.append(" AND created_by = :createdBy");
            params.put("createdBy", createdBy);
        }
        sql.append(" ORDER BY id DESC LIMIT 200");

        return jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("candidateType", type);
                    map.put("id", rs.getLong("id"));
                    if (hasColumn(rs, "project_id")) map.put("projectId", rs.getLong("project_id"));
                    if (hasColumn(rs, "status")) map.put("status", rs.getString("status"));
                    if (hasColumn(rs, "created_by")) map.put("createdBy", rs.getLong("created_by"));
                    if (hasColumn(rs, "created_at") && rs.getTimestamp("created_at") != null)
                        map.put("createdAt", rs.getTimestamp("created_at").toString());

                    // 类型特定字段
                    switch (type) {
                        case "tom" -> {
                            map.put("name", rs.getString("name"));
                            map.put("modelType", rs.getString("model_type"));
                            map.put("description", rs.getString("description"));
                            map.put("sourceType", rs.getString("source_type"));
                            map.put("confidence", rs.getBigDecimal("confidence"));
                        }
                        case "summary" -> {
                            map.put("overview", rs.getString("overview"));
                            map.put("summaryScope", rs.getString("summary_scope"));
                            map.put("validityLabel", rs.getString("validity_label"));
                        }
                        case "correction" -> {
                            map.put("correctionType", rs.getString("correction_type"));
                            map.put("fieldName", rs.getString("source_text"));
                            map.put("originalValue", rs.getString("candidate_value"));
                            map.put("suggestedValue", rs.getString("confirmed_value"));
                        }
                        case "skill" -> {
                            map.put("skillName", rs.getString("skill_name"));
                            map.put("description", rs.getString("description"));
                        }
                        case "tool" -> {
                            map.put("toolName", rs.getString("tool_name"));
                            map.put("description", rs.getString("description"));
                        }
                    }
                    return map;
                }).list();
    }

    private boolean hasColumn(java.sql.ResultSet rs, String column) {
        try {
            rs.findColumn(column);
            return true;
        } catch (java.sql.SQLException e) {
            return false;
        }
    }

    // =====================================================================
    // 确认 / 驳回
    // =====================================================================

    @PostMapping("/{type}/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable String type, @PathVariable Long id, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        switch (type) {
            case "tom" -> miniTomService.confirmCandidate(id, user);
            case "summary" -> traceSummaryService.confirmSummary(id, null, user);
            case "correction" -> correctionSuggestionService.confirmSuggestion(id, null, null, user);
            case "skill" -> jdbcTemplate.update(
                    "UPDATE test_skill_template SET status = 'ACTIVE', updated_at = ? WHERE id = ?",
                    timeProvider.now(), id);
            case "tool" -> jdbcTemplate.update(
                    "UPDATE test_tool_template SET status = 'ACTIVE', updated_at = ? WHERE id = ?",
                    timeProvider.now(), id);
            default -> throw new BusinessException("不支持的候选类型: " + type);
        }
        return ApiResponse.ok(null);
    }

    @PostMapping("/{type}/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable String type, @PathVariable Long id,
            @RequestBody(required = false) RejectRequest request, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String reason = request != null ? request.reason() : null;
        switch (type) {
            case "tom" -> miniTomService.rejectCandidate(id, reason, user);
            case "summary" -> jdbcTemplate.update(
                    "UPDATE trace_summary SET status = 'REJECTED', rejected_by = ?, rejected_at = ?, rejected_reason = ? WHERE id = ?",
                    user.id(), timeProvider.now(), reason, id);
            case "correction" -> jdbcTemplate.update(
                    "UPDATE trace_correction_candidate SET status = 'REJECTED', rejected_by = ?, rejected_at = ?, rejected_reason = ? WHERE id = ?",
                    user.id(), timeProvider.now(), reason, id);
            case "skill" -> jdbcTemplate.update(
                    "UPDATE test_skill_template SET status = 'DEPRECATED', updated_at = ? WHERE id = ?",
                    timeProvider.now(), id);
            case "tool" -> jdbcTemplate.update(
                    "UPDATE test_tool_template SET status = 'DEPRECATED', updated_at = ? WHERE id = ?",
                    timeProvider.now(), id);
            default -> throw new BusinessException("不支持的候选类型: " + type);
        }
        return ApiResponse.ok(null);
    }

    // =====================================================================
    // 批量操作
    // =====================================================================

    @PostMapping("/batch-confirm")
    public ApiResponse<Integer> batchConfirm(@RequestBody BatchRequest request, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        int count = 0;
        for (var item : request.items()) {
            try {
                confirm(item.type(), item.id(), auth);
                count++;
            } catch (Exception ignored) {
            }
        }
        return ApiResponse.ok(count);
    }

    @PostMapping("/batch-reject")
    public ApiResponse<Integer> batchReject(@RequestBody BatchRejectRequest request, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        int count = 0;
        for (var item : request.items()) {
            try {
                reject(item.type(), item.id(), new RejectRequest(request.reason()), auth);
                count++;
            } catch (Exception ignored) {
            }
        }
        return ApiResponse.ok(count);
    }

    // =====================================================================
    // 标记操作
    // =====================================================================

    @PostMapping("/{type}/{id}/mark-demo")
    public ApiResponse<Void> markDemo(@PathVariable String type, @PathVariable Long id) {
        updateValidityLabel(type, id, "DEMO");
        return ApiResponse.ok(null);
    }

    @PostMapping("/{type}/{id}/mark-dirty")
    public ApiResponse<Void> markDirty(@PathVariable String type, @PathVariable Long id) {
        updateValidityLabel(type, id, "DIRTY_DATA");
        return ApiResponse.ok(null);
    }

    private void updateValidityLabel(String type, Long id, String label) {
        String table = switch (type) {
            case "tom" -> "test_object_model";
            case "summary" -> "trace_summary";
            default -> throw new BusinessException("标记操作暂不支持类型: " + type);
        };
        jdbcTemplate.update("UPDATE " + table + " SET validity_label = ?, updated_at = ? WHERE id = ?",
                label, timeProvider.now(), id);
    }

    // =====================================================================
    // 请求记录
    // =====================================================================

    public record RejectRequest(String reason) {}
    public record CandidateRef(String type, Long id) {}
    public record BatchRequest(List<CandidateRef> items) {}
    public record BatchRejectRequest(List<CandidateRef> items, String reason) {}
}
