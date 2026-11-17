package com.company.aitest.yuque;

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
@RequestMapping("/api/projects/{projectId}/yuque/sync-tasks")
public class YuqueSyncTaskController {
    private final YuqueSyncTaskService service;

    public YuqueSyncTaskController(YuqueSyncTaskService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<YuqueSyncTaskRecord>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    @PostMapping
    public ApiResponse<YuqueSyncTaskRecord> create(@PathVariable Long projectId,
                                                   @Valid @RequestBody CreateRequest request,
                                                   Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(service.create(projectId, new YuqueSyncTaskService.CreateYuqueSyncTaskCommand(
                request.yuqueConfigId(), request.syncMode(), request.exportManifestRef()
        ), user));
    }

    public record CreateRequest(@NotNull Long yuqueConfigId, @NotBlank String syncMode, String exportManifestRef) {
    }
}
