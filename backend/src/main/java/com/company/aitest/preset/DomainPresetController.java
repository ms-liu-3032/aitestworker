package com.company.aitest.preset;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/domain-presets")
public class DomainPresetController {
    private final DomainPresetService domainPresetService;

    public DomainPresetController(DomainPresetService domainPresetService) {
        this.domainPresetService = domainPresetService;
    }

    @GetMapping
    public ApiResponse<List<DomainPresetDefinition>> listPresets() {
        return ApiResponse.ok(domainPresetService.listPresets());
    }

    @GetMapping("/default")
    public ApiResponse<DomainPresetDefinition> getDefaultPreset() {
        return ApiResponse.ok(domainPresetService.defaultPreset());
    }
}
