package com.company.aitest.dsh;

import com.company.aitest.audit.OperationLogService;
import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectRecord;
import com.company.aitest.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dsh/user/projects")
public class DshProjectController {
    private final ProjectService projects;
    private final OperationLogService operationLogs;

    public DshProjectController(ProjectService projects, OperationLogService operationLogs) {
        this.projects = projects;
        this.operationLogs = operationLogs;
    }

    @PostMapping
    public ApiResponse<ProjectRecord> create(@Valid @RequestBody CreateRequest request, Authentication auth) {
        CurrentUser user = ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
        ProjectRecord project = projects.create(request.projectName().trim(), request.description(), user);
        operationLogs.recordQuietly(user.id(), "PROJECT_CREATE", "PROJECT", project.id(),
                "{\"projectName\":\"" + safe(project.projectName()) + "\"}");
        return ApiResponse.ok(project);
    }

    public record CreateRequest(@NotBlank String projectName, String description) {}

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
