package com.company.aitest.minitom;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
    private final HistoricalCaseTomCandidateService historicalCaseTomCandidateService;
    private final HistoricalCaseImportService historicalCaseImportService;
    private final ProjectAccessService projectAccessService;

    public MiniTomController(MiniTomService miniTomService,
                             ManualImportService manualImportService,
                             MiniTomCrossValidationService crossValidationService,
                             HistoricalCaseTomCandidateService historicalCaseTomCandidateService,
                             HistoricalCaseImportService historicalCaseImportService,
                             ProjectAccessService projectAccessService) {
        this.miniTomService = miniTomService;
        this.manualImportService = manualImportService;
        this.crossValidationService = crossValidationService;
        this.historicalCaseTomCandidateService = historicalCaseTomCandidateService;
        this.historicalCaseImportService = historicalCaseImportService;
        this.projectAccessService = projectAccessService;
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

    @PostMapping("/extract/from-formal-cases")
    public ApiResponse<HistoricalCaseTomCandidateService.ExtractionResult> extractFromFormalCases(
            @RequestParam Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        return ApiResponse.ok(historicalCaseTomCandidateService.extractProject(projectId, user));
    }

    @PostMapping("/historical-case-imports")
    public ApiResponse<HistoricalCaseImportService.ImportResult> importHistoricalCases(
            @RequestBody HistoricalCaseImportService.ImportCommand command, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(command.projectId(), user);
        return ApiResponse.ok(historicalCaseImportService.importCases(command, "PASTE", user));
    }

    @PostMapping(value = "/historical-case-imports/upload", consumes = "multipart/form-data")
    public ApiResponse<HistoricalCaseImportService.ImportResult> uploadHistoricalCases(
            @RequestParam Long projectId, @RequestParam(required = false) String batchName,
            @RequestParam(required = false) String businessDomain, @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        return ApiResponse.ok(historicalCaseImportService.importFile(projectId, batchName, businessDomain, file, user));
    }

    @GetMapping("/historical-case-imports")
    public ApiResponse<List<HistoricalCaseImportService.ImportBatchView>> listHistoricalCaseImports(
            @RequestParam Long projectId, Authentication authentication) {
        projectAccessService.ensureCanAccess(projectId, (CurrentUser) authentication.getPrincipal());
        return ApiResponse.ok(historicalCaseImportService.list(projectId));
    }

    @GetMapping("/relation-candidates")
    public ApiResponse<List<HistoricalCaseTomCandidateService.RelationCandidateView>> listRelationCandidates(
            @RequestParam Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(historicalCaseTomCandidateService.listRelationCandidates(projectId));
    }

    @PostMapping("/relation-candidates/manual")
    public ApiResponse<HistoricalCaseTomCandidateService.RelationCandidateView> proposeManualRelation(
            @RequestParam Long projectId,
            @RequestBody HistoricalCaseTomCandidateService.ManualRelationCommand command,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        return ApiResponse.ok(historicalCaseTomCandidateService.proposeManualRelation(projectId, command, user));
    }

    @GetMapping("/alias-candidates")
    public ApiResponse<List<HistoricalCaseTomCandidateService.AliasCandidateView>> listAliasCandidates(@RequestParam Long projectId, Authentication authentication) {
        projectAccessService.ensureCanAccess(projectId, (CurrentUser) authentication.getPrincipal());
        return ApiResponse.ok(historicalCaseTomCandidateService.listAliasCandidates(projectId));
    }

    @GetMapping("/impact-candidates")
    public ApiResponse<List<HistoricalCaseTomCandidateService.ImpactCandidateView>> listImpactCandidates(@RequestParam Long projectId, Authentication authentication) {
        projectAccessService.ensureCanAccess(projectId, (CurrentUser) authentication.getPrincipal());
        return ApiResponse.ok(historicalCaseTomCandidateService.listImpactCandidates(projectId));
    }

    @PostMapping("/relation-candidates/{id}/confirm")
    public ApiResponse<HistoricalCaseTomCandidateService.RelationCandidateView> confirmRelationCandidate(
            @PathVariable Long id, @RequestParam Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        return ApiResponse.ok(historicalCaseTomCandidateService.confirmRelation(projectId, id, user));
    }

    @PostMapping("/relation-candidates/{id}/reject")
    public ApiResponse<HistoricalCaseTomCandidateService.RelationCandidateView> rejectRelationCandidate(
            @PathVariable Long id, @RequestParam Long projectId,
            @RequestBody(required = false) RejectRequest request, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        return ApiResponse.ok(historicalCaseTomCandidateService.rejectRelation(projectId, id,
                request == null ? null : request.reason(), user));
    }

    @PostMapping("/alias-candidates/{id}/confirm")
    public ApiResponse<Void> confirmAliasCandidate(@PathVariable Long id, @RequestParam Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        historicalCaseTomCandidateService.reviewAlias(projectId, id, true, null, user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/alias-candidates/{id}/reject")
    public ApiResponse<Void> rejectAliasCandidate(@PathVariable Long id, @RequestParam Long projectId,
                                                   @RequestBody(required = false) RejectRequest request, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        historicalCaseTomCandidateService.reviewAlias(projectId, id, false, request == null ? null : request.reason(), user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/impact-candidates/{id}/confirm")
    public ApiResponse<Void> confirmImpactCandidate(@PathVariable Long id, @RequestParam Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        historicalCaseTomCandidateService.reviewImpact(projectId, id, true, null, user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/impact-candidates/{id}/reject")
    public ApiResponse<Void> rejectImpactCandidate(@PathVariable Long id, @RequestParam Long projectId,
                                                    @RequestBody(required = false) RejectRequest request, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        historicalCaseTomCandidateService.reviewImpact(projectId, id, false, request == null ? null : request.reason(), user);
        return ApiResponse.ok(null);
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
