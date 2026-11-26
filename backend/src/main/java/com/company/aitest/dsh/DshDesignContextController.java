package com.company.aitest.dsh;

import java.util.Map;
import java.util.LinkedHashMap;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import com.company.aitest.project.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/aitestwork/dsh/tools/projects/{projectId}")
public class DshDesignContextController {
    private final ProjectAccessService access;
    private final DshDesignContextService designContext;
    private final ProjectService projects;

    public DshDesignContextController(ProjectAccessService access, DshDesignContextService designContext,
                                      ProjectService projects) {
        this.access = access;
        this.designContext = designContext;
        this.projects = projects;
    }

    public record Request(String requirementText, String tomMode) {}

    @PostMapping("/design-context")
    public ApiResponse<Map<String, Object>> designContext(@PathVariable Long projectId,
                                                           @RequestBody Request request,
                                                           Authentication authentication) {
        CurrentUser user = ((DshConnectionService.DshPrincipal) authentication.getPrincipal()).user();
        access.ensureCanAccess(projectId, user);
        Map<String, Object> result = new LinkedHashMap<>(designContext.build(projectId,
                request.requirementText(), request.tomMode(), user));
        result.put("project", projects.get(projectId));
        return ApiResponse.ok(result);
    }
}
