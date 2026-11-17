package com.company.aitest.dsh;

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
@RequestMapping("/api/dsh")
public class DshBindingController {
    private final DshConnectionService connections;

    public DshBindingController(DshConnectionService connections) {
        this.connections = connections;
    }

    @PostMapping("/bind-codes")
    public ApiResponse<DshConnectionService.BindCode> createCode(Authentication authentication) {
        return ApiResponse.ok(connections.createBindCode((CurrentUser) authentication.getPrincipal()));
    }

    @PostMapping("/bind")
    public ApiResponse<DshConnectionService.Binding> bind(@Valid @RequestBody BindRequest request) {
        return ApiResponse.ok(connections.bind(request.code(), request.deviceName()));
    }

    @GetMapping("/connections")
    public ApiResponse<List<DshConnectionService.ConnectionView>> list(Authentication authentication) {
        return ApiResponse.ok(connections.list((CurrentUser) authentication.getPrincipal()));
    }

    @PostMapping("/connections/{connectionId}/revoke")
    public ApiResponse<Void> revoke(@PathVariable Long connectionId, Authentication authentication) {
        connections.revoke(connectionId, (CurrentUser) authentication.getPrincipal());
        return ApiResponse.ok(null);
    }

    public record BindRequest(@NotBlank String code, @NotBlank String deviceName) {}
}
