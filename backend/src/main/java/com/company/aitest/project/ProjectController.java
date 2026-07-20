package com.company.aitest.project;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.audit.OperationLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projectService;
    private final OperationLogService operationLogService;

    public ProjectController(ProjectService projectService, OperationLogService operationLogService) {
        this.projectService = projectService;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public ApiResponse<java.util.List<ProjectRecord>> list(Authentication authentication) {
        return ApiResponse.ok(projectService.list((CurrentUser) authentication.getPrincipal()));
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<ProjectRecord>> listPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        return ApiResponse.ok(projectService.listPage((CurrentUser) authentication.getPrincipal(), page, size, keyword, status));
    }

    @PostMapping
    public ApiResponse<ProjectRecord> create(@Valid @RequestBody CreateProjectRequest request, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        ProjectRecord project = projectService.create(request.projectName(), request.description(), user);
        operationLogService.recordQuietly(user.id(), "PROJECT_CREATE", "PROJECT", project.id(),
                "{\"projectName\":\"" + safe(project.projectName()) + "\"}");
        return ApiResponse.ok(project);
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectRecord> get(@PathVariable Long projectId) {
        return ApiResponse.ok(projectService.get(projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectRecord> update(
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectRequest request,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        ProjectRecord project = projectService.update(
                projectId,
                request.projectName(),
                request.description(),
                user);
        operationLogService.recordQuietly(user.id(), "PROJECT_UPDATE", "PROJECT", projectId,
                "{\"projectName\":\"" + safe(project.projectName()) + "\"}");
        return ApiResponse.ok(project);
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectService.delete(projectId, user);
        operationLogService.recordQuietly(user.id(), "PROJECT_DELETE", "PROJECT", projectId, "{}");
        return ApiResponse.ok(null);
    }

    @PostMapping("/{projectId}/restore")
    public ApiResponse<ProjectRecord> restore(@PathVariable Long projectId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        ProjectRecord project = projectService.restore(projectId, user);
        operationLogService.recordQuietly(user.id(), "PROJECT_RESTORE", "PROJECT", projectId, "{}");
        return ApiResponse.ok(project);
    }

    public record CreateProjectRequest(@NotBlank String projectName, String description) {
    }

    public record UpdateProjectRequest(@NotBlank String projectName, String description) {
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
