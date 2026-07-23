package com.company.aitest.generation.session;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/generation/local-cases")
public class GenerationCaseLibraryController {
    private final GenerationCaseLibraryService caseLibraryService;

    public GenerationCaseLibraryController(GenerationCaseLibraryService caseLibraryService) {
        this.caseLibraryService = caseLibraryService;
    }

    @GetMapping
    public ApiResponse<List<GenerationCaseLibraryService.LocalCaseDraftView>> list(@PathVariable Long projectId,
                                                                                    Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.list(projectId, user));
    }

    @GetMapping("/page")
    public ApiResponse<GenerationCaseLibraryService.LocalCaseDraftPage> listPage(@PathVariable Long projectId,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) String keyword,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> modules,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> priorities,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> statuses,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> sources,
                                                                                  @org.springframework.web.bind.annotation.RequestParam(required = false) List<String> scenarioTypes,
                                                                                  Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.listPage(projectId, page, size, keyword,
                modules, priorities, statuses, sources, scenarioTypes, user));
    }

    @GetMapping("/{draftId}")
    public ApiResponse<GenerationCaseLibraryService.LocalCaseDraftView> get(@PathVariable Long projectId,
                                                                             @PathVariable Long draftId,
                                                                             Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.getOwnedDraft(projectId, draftId, user));
    }

    @PatchMapping("/{draftId}")
    public ApiResponse<GenerationCaseLibraryService.LocalCaseDraftView> update(@PathVariable Long projectId,
                                                                                @PathVariable Long draftId,
                                                                                @RequestBody Map<String, Object> body,
                                                                                Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        var cmd = new GenerationCaseLibraryService.UpdateLocalCaseCommand(
                stringValue(body.get("caseTitle")),
                stringValue(body.get("moduleName")),
                stringValue(body.get("precondition")),
                stringValue(body.get("steps")),
                stringValue(body.get("expectedResult")),
                stringValue(body.get("priority"))
        );
        return ApiResponse.ok(caseLibraryService.update(projectId, draftId, cmd, user));
    }

    @PostMapping("/{draftId}/confirm")
    public ApiResponse<GenerationCaseLibraryService.LocalCaseDraftView> confirm(@PathVariable Long projectId,
                                                                                 @PathVariable Long draftId,
                                                                                 Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.confirm(projectId, draftId, user));
    }

    @PostMapping("/{draftId}/duplicate")
    public ApiResponse<GenerationCaseLibraryService.LocalCaseDraftView> duplicate(@PathVariable Long projectId,
                                                                                   @PathVariable Long draftId,
                                                                                   Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.duplicate(projectId, draftId, user));
    }

    @PostMapping("/{draftId}/deprecate")
    public ApiResponse<GenerationCaseLibraryService.LocalCaseDraftView> deprecate(@PathVariable Long projectId,
                                                                                   @PathVariable Long draftId,
                                                                                   Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.deprecate(projectId, draftId, user));
    }

    @PostMapping("/{draftId}/submit")
    public ApiResponse<Void> submit(@PathVariable Long projectId,
                                    @PathVariable Long draftId,
                                    Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        caseLibraryService.submitToFormal(projectId, draftId, user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/batch/confirm")
    public ApiResponse<GenerationCaseLibraryService.BatchOperationResult> batchConfirm(@PathVariable Long projectId,
                                                                                         @RequestBody Map<String, Object> body,
                                                                                         Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.batchConfirm(projectId, longList(body.get("draftIds")), user));
    }

    @PostMapping("/batch/deprecate")
    public ApiResponse<GenerationCaseLibraryService.BatchOperationResult> batchDeprecate(@PathVariable Long projectId,
                                                                                           @RequestBody Map<String, Object> body,
                                                                                           Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.batchDeprecate(projectId, longList(body.get("draftIds")), user));
    }

    @PostMapping("/batch/submit")
    public ApiResponse<GenerationCaseLibraryService.BatchOperationResult> batchSubmit(@PathVariable Long projectId,
                                                                                        @RequestBody Map<String, Object> body,
                                                                                        Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(caseLibraryService.batchSubmitToFormal(projectId, longList(body.get("draftIds")), user));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<Long> longList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream()
                .filter(item -> item instanceof Number || item instanceof String)
                .map(item -> {
                    try {
                        return Long.valueOf(String.valueOf(item));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
