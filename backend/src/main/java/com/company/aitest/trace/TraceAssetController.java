package com.company.aitest.trace;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.session.GenerationCaseLibraryService;
import com.company.aitest.audit.OperationLogService;
import com.company.aitest.project.ProjectAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trace")
public class TraceAssetController {
    private final TraceAssetService traceAssetService;
    private final TraceSummaryService traceSummaryService;
    private final ProjectAccessService projectAccessService;
    private final OperationLogService operationLogService;

    public TraceAssetController(TraceAssetService traceAssetService, TraceSummaryService traceSummaryService,
                                ProjectAccessService projectAccessService, OperationLogService operationLogService) {
        this.traceAssetService = traceAssetService;
        this.traceSummaryService = traceSummaryService;
        this.projectAccessService = projectAccessService;
        this.operationLogService = operationLogService;
    }

    /**
     * 摘要生成已切到 trace_summary 正式承载层。
     * 保留旧端点但内部转发到 TraceSummaryService，前端可逐步迁移到 /summaries:generate。
     */
    @PostMapping("/groups/{groupId}/summary:generate")
    public ApiResponse<TraceSummaryRecord> generateSummary(@PathVariable Long groupId,
                                                           @Valid @RequestBody GenerateSummaryRequest request,
                                                           Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.generateSummary(groupId,
                new TraceSummaryService.GenerateSummaryCommand(request.modelConfigId(), request.traceSessionId(),
                        request.issueClipId(), request.summaryScope()), user));
    }

    @GetMapping("/groups/{groupId}/clean-steps")
    public ApiResponse<List<TraceAssetService.CleanStepView>> listCleanSteps(@PathVariable Long groupId,
                                                                             @RequestParam(required = false) Long traceSessionId,
                                                                             @RequestParam(required = false) Long issueClipId,
                                                                             Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listCleanSteps(groupId, traceSessionId, issueClipId, user));
    }

    @PostMapping("/groups/{groupId}/skills:generate")
    public ApiResponse<TestSkillTemplateRecord> generateSkill(@PathVariable Long groupId,
                                                              @Valid @RequestBody GenerateAssetRequest request,
                                                              Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.generateSkillTemplate(groupId,
                new TraceAssetService.GenerateAssetCommand(request.modelConfigId(), request.traceSessionId(),
                        request.issueClipId()), user));
    }

    @GetMapping("/groups/{groupId}/skills")
    public ApiResponse<List<TestSkillTemplateRecord>> listSkills(@PathVariable Long groupId,
                                                                 Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listSkillTemplates(groupId, user));
    }

    @PostMapping("/groups/{groupId}/tools:generate")
    public ApiResponse<List<TestToolTemplateRecord>> generateTools(@PathVariable Long groupId,
                                                                   @Valid @RequestBody GenerateAssetRequest request,
                                                                   Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.generateToolTemplates(groupId,
                new TraceAssetService.GenerateAssetCommand(request.modelConfigId(), request.traceSessionId(),
                        request.issueClipId()), user));
    }

    @GetMapping("/groups/{groupId}/tools")
    public ApiResponse<List<TestToolTemplateRecord>> listTools(@PathVariable Long groupId,
                                                               Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listToolTemplates(groupId, user));
    }

    @GetMapping("/projects/{projectId}/skills")
    public ApiResponse<List<TestSkillTemplateRecord>> listProjectSkills(@PathVariable Long projectId,
                                                                          Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(traceAssetService.listProjectSkillTemplates(projectId, user));
    }

    @PatchMapping("/projects/{projectId}/skills/{skillId}")
    public ApiResponse<TestSkillTemplateRecord> updateProjectSkill(@PathVariable Long projectId,
                                                                     @PathVariable Long skillId,
                                                                     @RequestBody Map<String, String> body,
                                                                     Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        TestSkillTemplateRecord result = traceAssetService.updateProjectSkillTemplate(projectId, skillId,
                body.get("skillName"), body.get("applicableScene"), body.get("flowSteps"), user);
        operationLogService.recordQuietly(user.id(), "PROJECT_ASSET_EDIT_SKILL", "TEST_SKILL_TEMPLATE", skillId,
                "{\"projectId\":" + projectId + "}");
        return ApiResponse.ok(result);
    }

    @GetMapping("/projects/{projectId}/tools")
    public ApiResponse<List<TestToolTemplateRecord>> listProjectTools(@PathVariable Long projectId,
                                                                        Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(traceAssetService.listProjectToolTemplates(projectId, user));
    }

    @PatchMapping("/projects/{projectId}/tools/{toolId}")
    public ApiResponse<TestToolTemplateRecord> updateProjectTool(@PathVariable Long projectId,
                                                                   @PathVariable Long toolId,
                                                                   @RequestBody Map<String, String> body,
                                                                   Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        TestToolTemplateRecord result = traceAssetService.updateProjectToolTemplate(projectId, toolId,
                body.get("toolName"), body.get("operationSteps"), user);
        operationLogService.recordQuietly(user.id(), "PROJECT_ASSET_EDIT_TOOL", "TEST_TOOL_TEMPLATE", toolId,
                "{\"projectId\":" + projectId + "}");
        return ApiResponse.ok(result);
    }

    @PostMapping("/groups/{groupId}/cases:generate")
    public ApiResponse<List<TraceGeneratedCaseRecord>> generateCases(@PathVariable Long groupId,
                                                                     @Valid @RequestBody GenerateCasesRequest request,
                                                                     Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.generateCases(groupId,
                new TraceAssetService.GenerateCasesCommand(request.modelConfigId(), request.caseType(),
                        request.traceSessionId(), request.issueClipId()), user));
    }

    @GetMapping("/projects/{projectId}/generated-cases")
    public ApiResponse<List<TraceGeneratedCaseRecord>> listGeneratedCases(@PathVariable Long projectId,
                                                                          Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listGeneratedCases(projectId, user));
    }

    /**
     * 项目资产页读取已确认轨迹摘要。不能复用管理员资产接口，否则普通项目成员会因权限看到空列表。
     */
    @GetMapping("/projects/{projectId}/summaries")
    public ApiResponse<List<TraceSummaryRecord>> listProjectSummaries(@PathVariable Long projectId,
                                                                        Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(traceSummaryService.listConfirmedByProject(projectId));
    }

    @GetMapping("/projects/{projectId}/generated-cases/page")
    public ApiResponse<TraceAssetService.GeneratedCasePage> listGeneratedCasesPage(
            @PathVariable Long projectId,
            @org.springframework.web.bind.annotation.RequestParam Long traceGroupId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listGeneratedCasePage(projectId, traceGroupId, page, size, user));
    }

    @GetMapping("/projects/{projectId}/formal-cases")
    public ApiResponse<List<TraceAssetService.TestCaseAssetView>> listFormalCases(@PathVariable Long projectId,
                                                                                   Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listFormalCases(projectId, user));
    }

    @GetMapping("/projects/{projectId}/formal-cases/page")
    public ApiResponse<TraceAssetService.FormalCasePage> listFormalCasesPage(@PathVariable Long projectId,
                                                                              @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                                                              @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) String keyword,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> modules,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> priorities,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> statuses,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> sources,
                                                                              @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> scenarioTypes,
                                                                              Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listFormalCasePage(projectId, page, size, keyword,
                modules, priorities, statuses, sources, scenarioTypes, user));
    }

    @GetMapping("/projects/{projectId}/formal-cases/{caseId}")
    public ApiResponse<TraceAssetService.TestCaseAssetView> getFormalCase(@PathVariable Long projectId,
                                                                            @PathVariable Long caseId,
                                                                            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.getFormalCase(projectId, caseId, user));
    }

    @DeleteMapping("/projects/{projectId}/formal-cases/{caseId}")
    public ApiResponse<Void> deleteFormalCase(@PathVariable Long projectId,
                                               @PathVariable Long caseId,
                                               Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        traceAssetService.deleteFormalCase(projectId, caseId, user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/projects/{projectId}/formal-cases/batch/delete")
    public ApiResponse<GenerationCaseLibraryService.BatchOperationResult> deleteFormalCases(@PathVariable Long projectId,
                                                                                               @RequestBody Map<String, List<Long>> body,
                                                                                               Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.deleteFormalCases(projectId, body.get("caseIds"), user));
    }

    @PostMapping("/generated-cases/{generatedCaseId}/submit")
    public ApiResponse<TraceAssetService.TestCaseAssetView> submitGeneratedCase(@PathVariable Long generatedCaseId,
                                                                                Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.submitGeneratedCase(generatedCaseId, user));
    }

    public record GenerateSummaryRequest(@NotNull Long modelConfigId, Long traceSessionId, Long issueClipId,
                                          String summaryScope) {
    }

    public record GenerateAssetRequest(@NotNull Long modelConfigId, Long traceSessionId, Long issueClipId) {
    }

    public record GenerateCasesRequest(@NotNull Long modelConfigId, String caseType, Long traceSessionId,
                                       Long issueClipId) {
    }
}
