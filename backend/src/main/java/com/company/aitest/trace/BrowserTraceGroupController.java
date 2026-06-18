package com.company.aitest.trace;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trace/projects/{projectId}/trace-groups")
public class BrowserTraceGroupController {
    private final BrowserTraceGroupService browserTraceGroupService;

    public BrowserTraceGroupController(BrowserTraceGroupService browserTraceGroupService) {
        this.browserTraceGroupService = browserTraceGroupService;
    }

    @PostMapping
    public ApiResponse<BrowserTraceGroupRecord> create(@PathVariable Long projectId,
                                                        @Valid @RequestBody CreateTraceGroupRequest request,
                                                        Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserTraceGroupService.create(projectId, request.groupName(),
                request.description(), request.profileId(), user));
    }

    @GetMapping
    public ApiResponse<List<BrowserTraceGroupRecord>> list(@PathVariable Long projectId,
                                                            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserTraceGroupService.list(projectId, user));
    }

    @GetMapping("/{groupId}")
    public ApiResponse<BrowserTraceGroupRecord> get(@PathVariable Long projectId, @PathVariable Long groupId,
                                                     Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(browserTraceGroupService.get(projectId, groupId, user));
    }

    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long groupId,
                                     Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        browserTraceGroupService.delete(projectId, groupId, user);
        return ApiResponse.ok(null);
    }

    public record CreateTraceGroupRequest(@NotBlank String groupName, String description, Long profileId) {
    }
}
