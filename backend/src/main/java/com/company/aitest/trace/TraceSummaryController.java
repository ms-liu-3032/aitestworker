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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轨迹摘要正式承载层 REST API。
 *
 * @see docs/handover/10_轨迹摘要文本化P0设计与实现方案.md §9
 */
@RestController
@RequestMapping("/api/trace")
public class TraceSummaryController {

    private final TraceSummaryService traceSummaryService;

    public TraceSummaryController(TraceSummaryService traceSummaryService) {
        this.traceSummaryService = traceSummaryService;
    }

    @PostMapping("/groups/{groupId}/summaries:generate")
    public ApiResponse<TraceSummaryRecord> generateSummary(@PathVariable Long groupId,
                                                           @Valid @RequestBody GenerateSummaryRequest request,
                                                           Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.generateSummary(groupId,
                new TraceSummaryService.GenerateSummaryCommand(request.modelConfigId(),
                        request.traceSessionId(), request.issueClipId(), request.summaryScope()), user));
    }

    @GetMapping("/groups/{groupId}/summaries")
    public ApiResponse<List<TraceSummaryRecord>> listSummaries(@PathVariable Long groupId,
                                                               Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.listSummaries(groupId, user));
    }

    @GetMapping("/summaries/{summaryId}")
    public ApiResponse<TraceSummaryRecord> getSummary(@PathVariable Long summaryId,
                                                      Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.getSummary(summaryId, user));
    }

    @PostMapping("/summaries/{summaryId}:regenerate")
    public ApiResponse<TraceSummaryRecord> regenerateSummary(@PathVariable Long summaryId,
                                                             @Valid @RequestBody RegenerateSummaryRequest request,
                                                             Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.regenerateSummary(summaryId,
                request.modelConfigId(), user));
    }

    @PutMapping("/summaries/{summaryId}")
    public ApiResponse<TraceSummaryRecord> updateSummary(@PathVariable Long summaryId,
                                                         @Valid @RequestBody UpdateSummaryRequest request,
                                                         Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.updateSummary(summaryId,
                new TraceSummaryService.UpdateSummaryCommand(request.overview(), request.businessSummary(),
                        request.keyStepsJson(), request.keyApiJson(), request.exceptionSummary(),
                        request.caseGenerationSuggestionJson(), request.validityLabel(),
                        request.pendingConfirmationJson()), user));
    }

    @PostMapping("/summaries/{summaryId}:confirm")
    public ApiResponse<TraceSummaryRecord> confirmSummary(@PathVariable Long summaryId,
                                                          @Valid @RequestBody ConfirmSummaryRequest request,
                                                          Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.confirmSummary(summaryId, request.validityLabel(), user));
    }

    @PostMapping("/summaries/{summaryId}:reject")
    public ApiResponse<TraceSummaryRecord> rejectSummary(@PathVariable Long summaryId,
                                                         @Valid @RequestBody RejectSummaryRequest request,
                                                         Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceSummaryService.rejectSummary(summaryId, request.reason(), user));
    }

    public record GenerateSummaryRequest(@NotNull Long modelConfigId, Long traceSessionId,
                                         Long issueClipId, String summaryScope) {
    }

    public record RegenerateSummaryRequest(Long modelConfigId) {
    }

    public record UpdateSummaryRequest(String overview, String businessSummary,
                                        String keyStepsJson, String keyApiJson,
                                        String exceptionSummary, String caseGenerationSuggestionJson,
                                        String validityLabel, String pendingConfirmationJson) {
    }

    public record ConfirmSummaryRequest(String validityLabel) {
    }

    public record RejectSummaryRequest(String reason) {
    }
}
