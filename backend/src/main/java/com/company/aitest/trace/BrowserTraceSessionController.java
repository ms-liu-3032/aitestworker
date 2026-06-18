package com.company.aitest.trace;

import java.util.List;

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

@RestController
@RequestMapping("/api/trace/projects/{projectId}/trace-groups/{groupId}/sessions")
public class BrowserTraceSessionController {
    private final BrowserTraceSessionService browserTraceSessionService;

    public BrowserTraceSessionController(BrowserTraceSessionService browserTraceSessionService) {
        this.browserTraceSessionService = browserTraceSessionService;
    }

    @PostMapping
    public ApiResponse<BrowserTraceSessionRecord> create(@PathVariable Long projectId, @PathVariable Long groupId,
                                                          @Valid @RequestBody CreateSessionRequest request,
                                                          Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserTraceSessionService.create(projectId, groupId, request.profileId(),
                request.sessionName(), request.browserType(), request.browserExecutablePath(), user));
    }

    @GetMapping
    public ApiResponse<List<BrowserTraceSessionRecord>> list(@PathVariable Long projectId,
                                                              @PathVariable Long groupId,
                                                              Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserTraceSessionService.list(projectId, groupId, user));
    }

    public record CreateSessionRequest(@NotNull Long profileId, String sessionName,
                                       @NotBlank String browserType, String browserExecutablePath) {
    }
}
