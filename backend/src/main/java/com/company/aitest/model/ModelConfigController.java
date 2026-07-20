package com.company.aitest.model;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.audit.OperationLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/model-configs")
public class ModelConfigController {
    private final ModelConfigService service;
    private final OperationLogService operationLogService;

    public ModelConfigController(ModelConfigService service, OperationLogService operationLogService) {
        this.service = service;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<ModelConfigRecord>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<ModelConfigRecord> create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        var command = new ModelConfigService.CreateModelConfigCommand(
                request.configName(), request.provider(), request.modelName(), request.endpoint(), request.apiKey());
        ModelConfigRecord created = service.create(command, user);
        operationLogService.recordQuietly(user.id(), "MODEL_CONFIG_CREATE", "MODEL_CONFIG", created.id(),
                detail(request.provider(), request.modelName()));
        return ApiResponse.ok(created);
    }

    public record CreateRequest(@NotBlank String configName, @NotBlank String provider, @NotBlank String modelName,
                                String endpoint, String apiKey) {
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<ModelConfigRecord> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request,
                                                 Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        var command = new ModelConfigService.UpdateModelConfigCommand(
                request.configName(), request.provider(), request.modelName(), request.endpoint(), request.apiKey());
        ModelConfigRecord updated = service.update(id, command);
        operationLogService.recordQuietly(user.id(), "MODEL_CONFIG_UPDATE", "MODEL_CONFIG", id,
                detail(request.provider(), request.modelName()));
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        service.delete(id);
        operationLogService.recordQuietly(user.id(), "MODEL_CONFIG_DELETE", "MODEL_CONFIG", id, "{}");
        return ApiResponse.ok(null);
    }

    public record UpdateRequest(@NotBlank String configName, @NotBlank String provider, @NotBlank String modelName,
                                String endpoint, String apiKey) {
    }

    private String detail(String provider, String modelName) {
        return "{\"provider\":\"" + safe(provider) + "\",\"modelName\":\"" + safe(modelName) + "\"}";
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
