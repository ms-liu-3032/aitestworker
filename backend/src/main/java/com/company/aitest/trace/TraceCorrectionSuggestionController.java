package com.company.aitest.trace;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轨迹定位与语义修正建议 REST API。
 *
 * @see docs/handover/11_轨迹定位与语义修正建议设计方案.md §8
 */
@RestController
@RequestMapping("/api/trace")
public class TraceCorrectionSuggestionController {

    private final TraceCorrectionSuggestionService correctionService;

    public TraceCorrectionSuggestionController(TraceCorrectionSuggestionService correctionService) {
        this.correctionService = correctionService;
    }

    @PostMapping("/groups/{groupId}/corrections:generate")
    public ApiResponse<List<TraceCorrectionCandidateRecord>> generateCorrections(
            @PathVariable Long groupId,
            @Valid @RequestBody GenerateCorrectionsRequest request,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(correctionService.generateSuggestions(
                groupId,
                request.scope() != null ? request.scope() : "GROUP",
                request.traceSessionId(),
                request.issueClipId(),
                request.modelConfigId(),
                user));
    }

    @GetMapping("/groups/{groupId}/corrections")
    public ApiResponse<List<TraceCorrectionCandidateRecord>> listCorrections(
            @PathVariable Long groupId,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(correctionService.listSuggestions(groupId, user));
    }

    @PostMapping("/corrections/{id}:confirm")
    public ApiResponse<TraceCorrectionCandidateRecord> confirmCorrection(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ConfirmCorrectionRequest request,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        String confirmedValue = request != null ? request.confirmedValue() : null;
        String confirmedStepText = request != null ? request.confirmedStepText() : null;
        return ApiResponse.ok(correctionService.confirmSuggestion(id, confirmedValue, confirmedStepText, user));
    }

    @PostMapping("/corrections/{id}:reject")
    public ApiResponse<TraceCorrectionCandidateRecord> rejectCorrection(
            @PathVariable Long id,
            @Valid @RequestBody RejectCorrectionRequest request,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(correctionService.rejectSuggestion(id, request.reason(), user));
    }

    public record GenerateCorrectionsRequest(
            @NotNull Long modelConfigId,
            String scope,
            Long traceSessionId,
            Long issueClipId) {
    }

    public record ConfirmCorrectionRequest(
            String confirmedValue,
            String confirmedStepText) {
    }

    @PostMapping("/groups/{groupId}/step-corrections")
    public ApiResponse<TraceCorrectionCandidateRecord> saveStepCorrection(
            @PathVariable Long groupId,
            @Valid @RequestBody SaveStepCorrectionRequest request,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(correctionService.saveManualStepCorrection(
                groupId,
                request.traceSessionId(),
                request.issueClipId(),
                request.stepNo(),
                request.sourceText(),
                request.operationType(),
                request.confirmedStepText(),
                request.relatedStepNo(),
                user));
    }

    /**
     * 应用已确认的步骤级修正到当前轨迹清洗后步骤。
     */
    @PostMapping("/groups/{groupId}/step-corrections:apply")
    public ApiResponse<List<TraceStepNormalizer.CleanTraceStep>> applyStepCorrections(
            @PathVariable Long groupId,
            @Valid @RequestBody ApplyStepCorrectionsRequest request,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        List<TraceCorrectionCandidateRecord> corrections = correctionService.getConfirmedForSummary(
                groupId, request.traceSessionId(), request.issueClipId());
        List<TraceStepNormalizer.CleanTraceStep> result = correctionService.applyStepCorrectionsToCleanSteps(
                request.steps(), corrections);
        return ApiResponse.ok(result);
    }

    /**
     * 基于已确认修正学习轻量模式。
     */
    @PostMapping("/groups/{groupId}/corrections:learn-pattern")
    public ApiResponse<Map<String, Integer>> learnPatterns(
            @PathVariable Long groupId,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        int count = correctionService.learnPatternFromConfirmedSuggestions(groupId, user);
        return ApiResponse.ok(Map.of("learnedPatterns", count));
    }

    public record RejectCorrectionRequest(
            @NotBlank String reason) {
    }

    public record ApplyStepCorrectionsRequest(
            Long traceSessionId,
            Long issueClipId,
            List<TraceStepNormalizer.CleanTraceStep> steps) {
    }

    public record SaveStepCorrectionRequest(
            Long traceSessionId,
            Long issueClipId,
            @NotNull Integer stepNo,
            @NotBlank String sourceText,
            @NotBlank String operationType,
            String confirmedStepText,
            Integer relatedStepNo) {
    }
}
