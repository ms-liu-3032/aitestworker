package com.company.aitest.trace;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trace/projects/{projectId}/profiles")
public class BrowserProfileController {
    private final BrowserProfileService browserProfileService;

    public BrowserProfileController(BrowserProfileService browserProfileService) {
        this.browserProfileService = browserProfileService;
    }

    @PostMapping
    public ApiResponse<BrowserProfileRecord> create(@PathVariable Long projectId,
                                                     @Valid @RequestBody CreateProfileRequest request,
                                                     Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserProfileService.create(projectId, request.profileName(),
                request.targetHost(), request.accountLabel(), request.roleLabel(),
                request.username(), request.password(), user));
    }

    @GetMapping
    public ApiResponse<List<BrowserProfileRecord>> list(@PathVariable Long projectId,
                                                         Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserProfileService.list(projectId, user));
    }

    @GetMapping("/{profileId}")
    public ApiResponse<BrowserProfileRecord> get(@PathVariable Long projectId, @PathVariable Long profileId,
                                                  Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserProfileService.get(projectId, profileId, user));
    }

    @GetMapping("/{profileId}/credentials")
    public ApiResponse<ProfileCredentialsResponse> getCredentials(@PathVariable Long projectId,
                                                                   @PathVariable Long profileId,
                                                                   Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserProfileService.getCredentials(projectId, profileId, user));
    }

    @PatchMapping("/{profileId}")
    public ApiResponse<BrowserProfileRecord> update(@PathVariable Long projectId,
                                                     @PathVariable Long profileId,
                                                     @RequestBody UpdateProfileRequest request,
                                                     Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserProfileService.update(projectId, profileId, request.profileName(),
                request.targetHost(), request.accountLabel(), request.roleLabel(),
                request.username(), request.password(), request.status(), user));
    }

    @PostMapping("/{profileId}/operations")
    public ApiResponse<BrowserProfileOperationRecord> logOperation(@PathVariable Long projectId,
                                                                    @PathVariable Long profileId,
                                                                    @Valid @RequestBody CreateOperationRequest request,
                                                                    Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserProfileService.logOperation(projectId, profileId,
                request.operationType(), request.operationDetail(), user));
    }

    public record CreateProfileRequest(@NotBlank String profileName, String targetHost, String accountLabel,
                                       String roleLabel, String username, String password) {
    }

    public record CreateOperationRequest(@NotBlank String operationType, String operationDetail) {
    }

    public record UpdateProfileRequest(String profileName, String targetHost, String accountLabel, String roleLabel,
                                       String username, String password, String status) {
    }

    public record ProfileCredentialsResponse(String username, String password) {
    }
}
