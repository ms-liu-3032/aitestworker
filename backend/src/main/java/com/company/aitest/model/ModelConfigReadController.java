package com.company.aitest.model;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-configs")
public class ModelConfigReadController {
    private final ModelConfigService service;

    public ModelConfigReadController(ModelConfigService service) {
        this.service = service;
    }

    @GetMapping("/enabled")
    public ApiResponse<List<ModelConfigRecord>> listEnabled() {
        return ApiResponse.ok(service.listEnabled());
    }
}

