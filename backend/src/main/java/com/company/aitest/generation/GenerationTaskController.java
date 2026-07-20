package com.company.aitest.generation;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/generation/tasks")
@PreAuthorize("isAuthenticated()")
public class GenerationTaskController {

    private final GenerationTaskService taskService;
    private final AsyncGenerationTaskService asyncTaskService;
    private final ProjectAccessService projectAccessService;

    public GenerationTaskController(GenerationTaskService taskService,
                                    AsyncGenerationTaskService asyncTaskService,
                                    ProjectAccessService projectAccessService) {
        this.taskService = taskService;
        this.asyncTaskService = asyncTaskService;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping
    public ApiResponse<List<GenerationTaskRecord>> list(@PathVariable Long projectId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(taskService.list(projectId));
    }

    @PostMapping
    public ApiResponse<AsyncGenerationTaskService.TaskView> create(
            @PathVariable Long projectId,
            @RequestBody AsyncGenerationTaskService.CreateAsyncTaskCommand command,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(asyncTaskService.createOrReuse(projectId, command, user));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AsyncGenerationTaskService.TaskView> get(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(asyncTaskService.get(projectId, taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<AsyncGenerationTaskService.TaskView> retry(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(asyncTaskService.retry(projectId, taskId, user));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<AsyncGenerationTaskService.TaskView> cancel(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(asyncTaskService.cancel(projectId, taskId));
    }
}
