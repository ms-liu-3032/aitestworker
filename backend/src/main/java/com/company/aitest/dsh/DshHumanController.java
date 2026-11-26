package com.company.aitest.dsh;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.session.GenerationCaseLibraryService;
import com.company.aitest.project.ProjectAccessService;
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

@RestController
@RequestMapping("/api/aitestwork/dsh/user/projects/{projectId}")
public class DshHumanController {
    private final ProjectAccessService access;
    private final GenerationCaseLibraryService library;
    private final DshPreferenceService preferences;
    private final DshHumanReviewService reviews;
    private final DshWorkSessionService workSessions;

    public DshHumanController(ProjectAccessService access, GenerationCaseLibraryService library,
                              DshPreferenceService preferences, DshHumanReviewService reviews,
                              DshWorkSessionService workSessions) {
        this.access = access;
        this.library = library;
        this.preferences = preferences;
        this.reviews = reviews;
        this.workSessions = workSessions;
    }

    @GetMapping("/drafts")
    public ApiResponse<List<GenerationCaseLibraryService.LocalCaseDraftView>> drafts(@PathVariable Long projectId,
                                                                                       Authentication auth) {
        CurrentUser user = user(auth);
        access.ensureCanAccess(projectId, user);
        return ApiResponse.ok(library.list(projectId, user));
    }

    @GetMapping("/drafts/{draftId}")
    public ApiResponse<DshHumanReviewService.ReviewPreview> draft(@PathVariable Long projectId,
                                                                                @PathVariable Long draftId,
                                                                                Authentication auth) {
        return ApiResponse.ok(reviews.preview(projectId, draftId, user(auth)));
    }

    @PostMapping("/drafts/{draftId}/submit")
    public ApiResponse<Void> submit(@PathVariable Long projectId, @PathVariable Long draftId,
                                    @Valid @RequestBody SubmitRequest request, Authentication auth) {
        reviews.submit(projectId, draftId, request.reviewedTitle(), request.reviewedFingerprint(), user(auth));
        return ApiResponse.ok(null);
    }

    @GetMapping("/preference")
    public ApiResponse<DshPreferenceService.Preference> preference(@PathVariable Long projectId, Authentication auth) {
        return ApiResponse.ok(preferences.get(projectId, user(auth)));
    }

    @PutMapping("/preference")
    public ApiResponse<DshPreferenceService.Preference> savePreference(@PathVariable Long projectId,
                                                                        @Valid @RequestBody PreferenceRequest request,
                                                                        Authentication auth) {
        return ApiResponse.ok(preferences.save(projectId, request.styleText(), user(auth)));
    }

    @GetMapping("/preference-candidates")
    public ApiResponse<List<DshPreferenceService.Candidate>> preferenceCandidates(@PathVariable Long projectId,
                                                                                   Authentication auth) {
        return ApiResponse.ok(preferences.candidates(projectId, user(auth)));
    }

    @PostMapping("/preference-candidates/{candidateId}/accept")
    public ApiResponse<DshPreferenceService.Preference> acceptPreferenceCandidate(@PathVariable Long projectId,
                                                                                   @PathVariable Long candidateId,
                                                                                   @Valid @RequestBody PreferenceDecisionRequest request,
                                                                                   Authentication auth) {
        return ApiResponse.ok(preferences.accept(projectId, candidateId, request.reviewedHash(), user(auth)));
    }

    @PostMapping("/preference-candidates/{candidateId}/reject")
    public ApiResponse<DshPreferenceService.Candidate> rejectPreferenceCandidate(@PathVariable Long projectId,
                                                                                   @PathVariable Long candidateId,
                                                                                   @Valid @RequestBody PreferenceDecisionRequest request,
                                                                                   Authentication auth) {
        return ApiResponse.ok(preferences.reject(projectId, candidateId, request.reviewedHash(), user(auth)));
    }

    @GetMapping("/work-sessions/{sessionId}/stages")
    public ApiResponse<List<DshWorkSessionService.StageSnapshot>> stages(@PathVariable Long projectId,
                                                                            @PathVariable Long sessionId,
                                                                            Authentication auth) {
        return ApiResponse.ok(workSessions.stages(projectId, sessionId, user(auth)));
    }

    @PostMapping("/work-sessions/{sessionId}/stages/{stageKey}/confirm")
    public ApiResponse<DshWorkSessionService.StageSnapshot> confirmStage(@PathVariable Long projectId,
                                                                            @PathVariable Long sessionId,
                                                                            @PathVariable String stageKey,
                                                                            @Valid @RequestBody StageConfirmRequest request,
                                                                            Authentication auth) {
        return ApiResponse.ok(workSessions.confirm(projectId, sessionId, stageKey,
                request.reviewedHash(), user(auth)));
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }

    public record SubmitRequest(@NotNull String reviewedTitle, @NotNull String reviewedFingerprint) {}
    public record PreferenceRequest(@NotNull String styleText) {}
    public record PreferenceDecisionRequest(@NotNull String reviewedHash) {}
    public record StageConfirmRequest(@NotNull String reviewedHash) {}
}
