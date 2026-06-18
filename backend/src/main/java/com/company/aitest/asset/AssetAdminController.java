package com.company.aitest.asset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 管理后台正式测试资产库。
 */
@RestController
@RequestMapping("/api/admin/assets")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class AssetAdminController {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public AssetAdminController(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    // =====================================================================
    // 正式测试用例
    // =====================================================================

    @GetMapping("/formal-cases")
    public ApiResponse<List<Map<String, Object>>> listFormalCases(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String moduleName,
            @RequestParam(required = false) String status) {

        StringBuilder sql = new StringBuilder(
                "SELECT id, project_id, case_no, case_title, module_name, priority, case_status, created_at FROM test_case_asset WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (moduleName != null && !moduleName.isBlank()) {
            sql.append(" AND module_name = :moduleName");
            params.put("moduleName", moduleName);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND case_status = :status");
            params.put("status", status);
        }
        sql.append(" ORDER BY id DESC");

        return ApiResponse.ok(jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> row(
                        entry("id", rs.getLong("id")),
                        entry("projectId", rs.getLong("project_id")),
                        entry("caseNo", rs.getString("case_no")),
                        entry("caseTitle", rs.getString("case_title")),
                        entry("moduleName", rs.getString("module_name") != null ? rs.getString("module_name") : ""),
                        entry("priority", rs.getString("priority") != null ? rs.getString("priority") : ""),
                        entry("status", rs.getString("case_status") != null ? rs.getString("case_status") : ""),
                        entry("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "")
                )).list());
    }

    // =====================================================================
    // 测试点
    // =====================================================================

    @GetMapping("/test-points")
    public ApiResponse<List<Map<String, Object>>> listTestPoints(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {

        StringBuilder sql = new StringBuilder(
                "SELECT id, project_id, task_id, module_name, point_content, suggested_priority, assumption_confirm_status FROM test_point_draft WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        sql.append(" ORDER BY id DESC");

        return ApiResponse.ok(jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> row(
                        entry("id", rs.getLong("id")),
                        entry("projectId", rs.getLong("project_id")),
                        entry("pointContent", rs.getString("point_content") != null ? rs.getString("point_content") : ""),
                        entry("moduleName", rs.getString("module_name") != null ? rs.getString("module_name") : ""),
                        entry("priority", rs.getString("suggested_priority") != null ? rs.getString("suggested_priority") : ""),
                        entry("status", rs.getString("assumption_confirm_status") != null ? rs.getString("assumption_confirm_status") : "")
                )).list());
    }

    // =====================================================================
    // 知识片段
    // =====================================================================

    @GetMapping("/knowledge")
    public ApiResponse<List<Map<String, Object>>> listKnowledge(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {

        StringBuilder sql = new StringBuilder(
                "SELECT id, project_id, title, asset_ref_type, status, created_at FROM knowledge_asset WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        sql.append(" ORDER BY id DESC");

        return ApiResponse.ok(jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> row(
                        entry("id", rs.getLong("id")),
                        entry("projectId", rs.getLong("project_id")),
                        entry("title", rs.getString("title") != null ? rs.getString("title") : ""),
                        entry("assetRefType", rs.getString("asset_ref_type") != null ? rs.getString("asset_ref_type") : ""),
                        entry("status", rs.getString("status") != null ? rs.getString("status") : ""),
                        entry("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "")
                )).list());
    }

    // =====================================================================
    // 轨迹摘要
    // =====================================================================

    @GetMapping("/summaries")
    public ApiResponse<List<Map<String, Object>>> listSummaries(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {

        StringBuilder sql = new StringBuilder(
                "SELECT id, project_id, trace_group_id, summary_scope, overview, status, validity_label, confidence_label, created_at FROM trace_summary WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        sql.append(" ORDER BY id DESC");

        return ApiResponse.ok(jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> row(
                        entry("id", rs.getLong("id")),
                        entry("projectId", rs.getLong("project_id")),
                        entry("traceGroupId", rs.getLong("trace_group_id")),
                        entry("summaryScope", rs.getString("summary_scope") != null ? rs.getString("summary_scope") : ""),
                        entry("overview", rs.getString("overview") != null ? rs.getString("overview") : ""),
                        entry("status", rs.getString("status") != null ? rs.getString("status") : ""),
                        entry("validityLabel", rs.getString("validity_label") != null ? rs.getString("validity_label") : ""),
                        entry("confidenceLabel", rs.getString("confidence_label") != null ? rs.getString("confidence_label") : ""),
                        entry("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "")
                )).list());
    }

    // =====================================================================
    // Skill 模板
    // =====================================================================

    @GetMapping("/skills")
    public ApiResponse<List<Map<String, Object>>> listSkills(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {

        StringBuilder sql = new StringBuilder(
                "SELECT id, project_id, skill_name, description, status, created_at FROM test_skill_template WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        sql.append(" ORDER BY id DESC");

        return ApiResponse.ok(jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> row(
                        entry("id", rs.getLong("id")),
                        entry("projectId", rs.getLong("project_id")),
                        entry("skillName", rs.getString("skill_name") != null ? rs.getString("skill_name") : ""),
                        entry("description", rs.getString("description") != null ? rs.getString("description") : ""),
                        entry("status", rs.getString("status") != null ? rs.getString("status") : ""),
                        entry("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "")
                )).list());
    }

    // =====================================================================
    // Tool 模板
    // =====================================================================

    @GetMapping("/tools")
    public ApiResponse<List<Map<String, Object>>> listTools(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {

        StringBuilder sql = new StringBuilder(
                "SELECT id, project_id, tool_name, description, status, created_at FROM test_tool_template WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        sql.append(" ORDER BY id DESC");

        return ApiResponse.ok(jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> row(
                        entry("id", rs.getLong("id")),
                        entry("projectId", rs.getLong("project_id")),
                        entry("toolName", rs.getString("tool_name") != null ? rs.getString("tool_name") : ""),
                        entry("description", rs.getString("description") != null ? rs.getString("description") : ""),
                        entry("status", rs.getString("status") != null ? rs.getString("status") : ""),
                        entry("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "")
                )).list());
    }

    // =====================================================================
    // 弃用 / 恢复
    // =====================================================================

    @PostMapping("/{type}/{id}/deprecate")
    public ApiResponse<Void> deprecate(@PathVariable String type, @PathVariable Long id,
            @RequestBody(required = false) DeprecateRequest request, Authentication auth) {
        String table = resolveTableName(type);
        String statusCol = "formal-cases".equals(type) ? "case_status" : "status";
        jdbcTemplate.update("UPDATE " + table + " SET " + statusCol + " = 'DEPRECATED', updated_at = ? WHERE id = ?",
                timeProvider.now(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{type}/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable String type, @PathVariable Long id, Authentication auth) {
        String table = resolveTableName(type);
        String statusCol = "formal-cases".equals(type) ? "case_status" : "status";
        jdbcTemplate.update("UPDATE " + table + " SET " + statusCol + " = 'ACTIVE', updated_at = ? WHERE id = ?",
                timeProvider.now(), id);
        return ApiResponse.ok(null);
    }

    private String resolveTableName(String type) {
        return switch (type) {
            case "formal-cases" -> "test_case_asset";
            case "test-points" -> "test_point_draft";
            case "knowledge" -> "knowledge_asset";
            case "summaries" -> "trace_summary";
            case "skills" -> "test_skill_template";
            case "tools" -> "test_tool_template";
            default -> throw new BusinessException("不支持的资产类型: " + type);
        };
    }

    public record DeprecateRequest(String reason) {}

    @SafeVarargs
    private static Map<String, Object> row(Map.Entry<String, Object>... entries) {
        Map<String, Object> map = new HashMap<>();
        for (var e : entries) map.put(e.getKey(), e.getValue());
        return map;
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return Map.entry(key, value);
    }
}
