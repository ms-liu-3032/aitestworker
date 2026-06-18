package com.company.aitest.model;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
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

    public ModelConfigController(ModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<ModelConfigRecord>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<ModelConfigRecord> create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        var command = new ModelConfigService.CreateModelConfigCommand(
                request.configName(), request.provider(), request.modelName(), request.endpoint(), request.apiKey());
        return ApiResponse.ok(service.create(command, (CurrentUser) authentication.getPrincipal()));
    }

    public record CreateRequest(@NotBlank String configName, @NotBlank String provider, @NotBlank String modelName,
                                String endpoint, String apiKey) {
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<ModelConfigRecord> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        var command = new ModelConfigService.UpdateModelConfigCommand(
                request.configName(), request.provider(), request.modelName(), request.endpoint(), request.apiKey());
        return ApiResponse.ok(service.update(id, command));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    public record UpdateRequest(@NotBlank String configName, @NotBlank String provider, @NotBlank String modelName,
                                String endpoint, String apiKey) {
    }
}
