package com.company.aitest.dsh;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.session.GenerationCaseLibraryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dsh/tools/projects/{projectId}/local-cases")
public class DshLocalCaseController {
    private final DshLocalCaseService localCases;

    public DshLocalCaseController(DshLocalCaseService localCases) { this.localCases = localCases; }

    @GetMapping("/{localCaseId}")
    public ApiResponse<GenerationCaseLibraryService.LocalCaseDraftView> get(@PathVariable Long projectId,
            @PathVariable Long localCaseId, Authentication auth) {
        return ApiResponse.ok(localCases.get(projectId, localCaseId, user(auth)));
    }

    @PatchMapping("/{localCaseId}")
    public ApiResponse<DshLocalCaseService.UpdateResult> update(@PathVariable Long projectId,
            @PathVariable Long localCaseId, @RequestBody DshLocalCaseService.UpdateInput input, Authentication auth) {
        return ApiResponse.ok(localCases.update(projectId, localCaseId, input, user(auth)));
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }
}
