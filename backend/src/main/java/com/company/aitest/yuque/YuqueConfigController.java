package com.company.aitest.yuque;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/yuque/configs")
public class YuqueConfigController {
    private final YuqueConfigService service;

    public YuqueConfigController(YuqueConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<YuqueConfigRecord>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    @PostMapping
    public ApiResponse<YuqueConfigRecord> create(@PathVariable Long projectId,
                                                 @Valid @RequestBody CreateRequest request,
                                                 Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(service.create(projectId, new YuqueConfigService.CreateYuqueConfigCommand(
                request.configName(),
                request.host() == null || request.host().isBlank() ? "https://www.yuque.com" : request.host(),
                request.authMode(),
                request.username(),
                request.saveCredential() == null ? 0 : request.saveCredential(),
                request.repoScope(),
                request.exporterType() == null || request.exporterType().isBlank() ? "YUQUE_TOOLS" : request.exporterType(),
                request.enabled() == null ? 1 : request.enabled()
        ), user));
    }

    public record CreateRequest(@NotBlank String configName, String host, @NotBlank String authMode, String username,
                                Integer saveCredential, String repoScope, String exporterType, Integer enabled) {
    }
}
