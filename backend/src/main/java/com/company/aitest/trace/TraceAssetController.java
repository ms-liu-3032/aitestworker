package com.company.aitest.trace;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public TraceAssetController(TraceAssetService traceAssetService, TraceSummaryService traceSummaryService) {
        this.traceAssetService = traceAssetService;
        this.traceSummaryService = traceSummaryService;
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

    @GetMapping("/projects/{projectId}/formal-cases")
    public ApiResponse<List<TraceAssetService.TestCaseAssetView>> listFormalCases(@PathVariable Long projectId,
                                                                                   Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceAssetService.listFormalCases(projectId, user));
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
