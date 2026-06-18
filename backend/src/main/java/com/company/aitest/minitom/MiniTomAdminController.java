package com.company.aitest.minitom;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 管理后台 Mini-TOM 公共模型管理。
 */
@RestController
@RequestMapping("/api/admin/mini-tom")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class MiniTomAdminController {

    private final MiniTomService miniTomService;
    private final JdbcClient jdbc;

    public MiniTomAdminController(MiniTomService miniTomService, JdbcClient jdbc) {
        this.miniTomService = miniTomService;
        this.jdbc = jdbc;
    }

    // =====================================================================
    // TOM 列表（支持多维筛选）
    // =====================================================================

    @GetMapping("/models")
    public ApiResponse<List<TestObjectModelRecord>> listModels(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String businessDomain,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(required = false) Long confirmedBy) {

        StringBuilder sql = new StringBuilder("SELECT * FROM test_object_model WHERE 1=1");
        var params = new java.util.HashMap<String, Object>();

        if (projectId != null) {
            sql.append(" AND project_id = :projectId");
            params.put("projectId", projectId);
        }
        if (modelType != null && !modelType.isBlank()) {
            sql.append(" AND model_type = :modelType");
            params.put("modelType", modelType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            sql.append(" AND source_type = :sourceType");
            params.put("sourceType", sourceType);
        }
        if (businessDomain != null && !businessDomain.isBlank()) {
            sql.append(" AND business_domain = :businessDomain");
            params.put("businessDomain", businessDomain);
        }
        if (createdBy != null) {
            sql.append(" AND created_by = :createdBy");
            params.put("createdBy", createdBy);
        }
        if (confirmedBy != null) {
            sql.append(" AND confirmed_by = :confirmedBy");
            params.put("confirmedBy", confirmedBy);
        }

        sql.append(" ORDER BY id DESC");

        List<TestObjectModelRecord> result = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> miniTomService.mapTestObjectModel(rs, rowNum))
                .list();
        return ApiResponse.ok(result);
    }

    @GetMapping("/models/{id}")
    public ApiResponse<TestObjectModelRecord> getModel(@PathVariable Long id) {
        return ApiResponse.ok(miniTomService.getById(id));
    }

    // =====================================================================
    // 确认 / 驳回 / 编辑确认
    // =====================================================================

    @PostMapping("/models/{id}/confirm")
    public ApiResponse<TestObjectModelRecord> confirm(@PathVariable Long id, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(miniTomService.confirmCandidate(id, user));
    }

    @PostMapping("/models/{id}/reject")
    public ApiResponse<TestObjectModelRecord> reject(@PathVariable Long id,
            @RequestBody(required = false) MiniTomController.RejectRequest request, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String reason = request != null ? request.reason() : null;
        return ApiResponse.ok(miniTomService.rejectCandidate(id, reason, user));
    }

    @PostMapping("/models/{id}/edit-confirm")
    public ApiResponse<TestObjectModelRecord> editConfirm(@PathVariable Long id,
            @RequestBody MiniTomService.EditTomCommand command, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(miniTomService.editAndConfirm(id, command, user));
    }

    // =====================================================================
    // 弃用 / 恢复
    // =====================================================================

    @PostMapping("/models/{id}/deprecate")
    public ApiResponse<TestObjectModelRecord> deprecate(@PathVariable Long id,
            @RequestBody(required = false) DeprecateRequest request, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String reason = request != null ? request.reason() : null;
        return ApiResponse.ok(miniTomService.deprecateTom(id, reason, user));
    }

    @PostMapping("/models/{id}/restore")
    public ApiResponse<TestObjectModelRecord> restore(@PathVariable Long id, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(miniTomService.restoreTom(id, user));
    }

    // =====================================================================
    // 批量操作
    // =====================================================================

    @PostMapping("/candidates/batch-confirm")
    public ApiResponse<Integer> batchConfirm(@RequestBody BatchRequest request, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        int count = 0;
        for (Long id : request.ids()) {
            try {
                miniTomService.confirmCandidate(id, user);
                count++;
            } catch (Exception ignored) {
            }
        }
        return ApiResponse.ok(count);
    }

    @PostMapping("/candidates/batch-reject")
    public ApiResponse<Integer> batchReject(@RequestBody BatchRejectRequest request, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        int count = 0;
        for (Long id : request.ids()) {
            try {
                miniTomService.rejectCandidate(id, request.reason(), user);
                count++;
            } catch (Exception ignored) {
            }
        }
        return ApiResponse.ok(count);
    }

    // =====================================================================
    // 引用查询
    // =====================================================================

    @GetMapping("/models/{id}/references")
    public ApiResponse<List<Map<String, Object>>> getReferences(@PathVariable Long id) {
        List<Map<String, Object>> refs = jdbc.sql("""
                SELECT gt.id AS task_id, gt.task_name, gt.status, gt.created_at
                FROM generation_task gt
                WHERE gt.test_scope_snapshot LIKE :pattern
                ORDER BY gt.created_at DESC
                """)
                .param("pattern", "%" + id + "%")
                .query((rs, rowNum) -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("taskId", rs.getLong("task_id"));
                    map.put("taskName", rs.getString("task_name"));
                    map.put("status", rs.getString("status"));
                    map.put("createdAt", rs.getTimestamp("created_at"));
                    return map;
                })
                .list();
        return ApiResponse.ok(refs);
    }

    // =====================================================================
    // 请求记录
    // =====================================================================

    public record DeprecateRequest(String reason) {}
    public record BatchRequest(List<Long> ids) {}
    public record BatchRejectRequest(List<Long> ids, String reason) {}
}
