package com.company.aitest.prompt;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prompts")
public class PromptTemplateReadController {
    private final PromptTemplateService service;

    public PromptTemplateReadController(PromptTemplateService service) {
        this.service = service;
    }

    @GetMapping("/enabled")
    public ApiResponse<List<PromptTemplateRecord>> listEnabled() {
        return ApiResponse.ok(service.listEnabled());
    }
}

