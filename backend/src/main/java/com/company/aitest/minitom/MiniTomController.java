package com.company.aitest.minitom;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Mini-TOM REST API。
 * <p>
 * 用关系型数据库验证 Mini-TOM 可行性，不依赖 Weaviate / Neo4j。
 */
@RestController
@RequestMapping("/api/mini-tom")
public class MiniTomController {

    private final MiniTomService miniTomService;
    private final ManualImportService manualImportService;
    private final MiniTomCrossValidationService crossValidationService;

    public MiniTomController(MiniTomService miniTomService,
                             ManualImportService manualImportService,
                             MiniTomCrossValidationService crossValidationService) {
        this.miniTomService = miniTomService;
        this.manualImportService = manualImportService;
        this.crossValidationService = crossValidationService;
    }

    // =====================================================================
    // 抽取
    // =====================================================================

    @PostMapping("/extract/from-trace-summary/{summaryId}")
    public ApiResponse<List<TestObjectModelRecord>> extractFromTraceSummary(
            @PathVariable Long summaryId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.extractFromTraceSummary(summaryId, user));
    }

    @PostMapping("/extract/from-manual-section")
    public ApiResponse<List<TestObjectModelRecord>> extractFromManualSection(
            @RequestBody MiniTomService.ManualSectionCommand command, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.extractFromManualSection(command, user));
    }

    @PostMapping("/extract/from-test-assets")
    public ApiResponse<List<TestObjectModelRecord>> extractFromTestAssets(
            @RequestParam Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.extractFromTestAssets(projectId, user));
    }

    // =====================================================================
    // 候选管理
    // =====================================================================

    @GetMapping("/candidates")
    public ApiResponse<List<TestObjectModelRecord>> listCandidates(
            @RequestParam Long projectId,
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) String businessDomain,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.listCandidates(projectId, modelType, businessDomain, user));
    }

    @PostMapping("/candidates/{id}/confirm")
    public ApiResponse<TestObjectModelRecord> confirmCandidate(
            @PathVariable Long id, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.confirmCandidate(id, user));
    }

    @PostMapping("/candidates/{id}/reject")
    public ApiResponse<TestObjectModelRecord> rejectCandidate(
            @PathVariable Long id,
            @RequestBody(required = false) RejectRequest request,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        String reason = request != null ? request.reason() : null;
        return ApiResponse.ok(miniTomService.rejectCandidate(id, reason, user));
    }

    @PostMapping("/candidates/{id}/merge")
    public ApiResponse<TestObjectModelRecord> editAndConfirm(
            @PathVariable Long id,
            @RequestBody MiniTomService.EditTomCommand command,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.editAndConfirm(id, command, user));
    }

    // =====================================================================
    // ACTIVE TOM
    // =====================================================================

    @GetMapping("/active")
    public ApiResponse<List<TestObjectModelRecord>> listActive(
            @RequestParam Long projectId,
            @RequestParam(required = false) String modelType,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.listActive(projectId, modelType, user));
    }

    // =====================================================================
    // 测试范围构建
    // =====================================================================

    @PostMapping("/build-test-scope")
    public ApiResponse<MiniTomService.TestScopeResult> buildTestScope(
            @RequestBody BuildTestScopeRequest request, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.buildTestScope(
                request.projectId(), request.requirementText(), request.modelConfigId(), user));
    }

    // =====================================================================
    // 手册导入
    // =====================================================================

    @PostMapping("/import/manual")
    public ApiResponse<ManualImportService.ImportProgressResponse> importManual(
            @RequestBody ManualImportService.ImportManualCommand command,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(manualImportService.importManual(command, user));
    }

    @GetMapping("/import/tasks")
    public ApiResponse<List<ManualImportService.ImportProgressResponse>> listImportTasks(
            @RequestParam Long projectId) {
        return ApiResponse.ok(manualImportService.listTasks(projectId));
    }

    @GetMapping("/import/tasks/{taskId}")
    public ApiResponse<ManualImportService.ImportProgressResponse> getImportProgress(
            @PathVariable Long taskId) {
        return ApiResponse.ok(manualImportService.getProgress(taskId));
    }

    // =====================================================================
    // 交叉验证
    // =====================================================================

    @PostMapping("/cross-validate")
    public ApiResponse<List<MiniTomCrossValidationService.CrossValidationResult>> crossValidate(
            @RequestParam Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(crossValidationService.crossValidateProject(projectId, user));
    }

    // =====================================================================
    // 升级为系统级 TOM
    // =====================================================================

    @PostMapping("/candidates/{id}/upgrade")
    public ApiResponse<TestObjectModelRecord> upgradeToSystem(
            @PathVariable Long id, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(miniTomService.upgradeToSystemTom(id, user));
    }

    // =====================================================================
    // 请求记录
    // =====================================================================

    public record RejectRequest(String reason) {
    }

    public record BuildTestScopeRequest(Long projectId, String requirementText, Long modelConfigId) {
    }
}
