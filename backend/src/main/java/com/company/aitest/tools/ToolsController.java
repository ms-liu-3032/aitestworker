package com.company.aitest.tools;

import com.company.aitest.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolsController {
    private final ToolRegistry registry;

    public ToolsController(ToolRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/{toolCode}/generate")
    public ApiResponse<ToolGenerateResponse> generate(@PathVariable String toolCode,
                                                      @RequestBody(required = false) ToolGenerateRequest request) {
        ToolGenerateRequest actualRequest = request == null ? new ToolGenerateRequest(1, null) : request;
        return ApiResponse.ok(registry.get(toolCode).generate(actualRequest));
    }
}
