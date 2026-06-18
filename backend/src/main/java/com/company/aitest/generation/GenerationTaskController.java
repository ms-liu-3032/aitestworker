package com.company.aitest.generation;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/generation/tasks")
@PreAuthorize("isAuthenticated()")
public class GenerationTaskController {

    private final GenerationTaskService taskService;

    public GenerationTaskController(GenerationTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<List<GenerationTaskRecord>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(taskService.list(projectId));
    }
}
