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

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
