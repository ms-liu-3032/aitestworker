package com.company.aitest.trace;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trace/devices")
public class WorkerDeviceController {
    private final WorkerDeviceService workerDeviceService;

    public WorkerDeviceController(WorkerDeviceService workerDeviceService) {
        this.workerDeviceService = workerDeviceService;
    }

    @PostMapping("/bind-codes")
    public ApiResponse<WorkerDeviceService.BindCodeResponse> createBindCode(Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(workerDeviceService.createBindCode(user));
    }

    @PostMapping("/bind")
    public ApiResponse<WorkerDeviceRecord> bind(@Valid @RequestBody BindRequest request,
                                                 Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(workerDeviceService.bind(request.deviceName(), request.platform(),
                request.arch(), request.workerVersion(), request.protocolVersion(), user));
    }

    @PostMapping("/consume-bind-code")
    public ApiResponse<WorkerDeviceService.WorkerBindResponse> consumeBindCode(@Valid @RequestBody ConsumeBindCodeRequest request) {
        return ApiResponse.ok(workerDeviceService.consumeBindCode(request.code(), request.deviceName(),
                request.platform(), request.arch(), request.workerVersion(), request.protocolVersion()));
    }

    @PostMapping("/heartbeat")
    public ApiResponse<WorkerDeviceService.WorkerHeartbeatResponse> heartbeat(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.ok(workerDeviceService.heartbeat(authorization));
    }

    @GetMapping
    public ApiResponse<List<WorkerDeviceRecord>> list(Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(workerDeviceService.list(user));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<Void> revoke(@PathVariable Long id, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        workerDeviceService.revoke(id, user);
        return ApiResponse.ok(null);
    }

    public record BindRequest(@NotBlank String deviceName, @NotBlank String platform, @NotBlank String arch,
                              @NotBlank String workerVersion, @NotBlank String protocolVersion) {
    }

    public record ConsumeBindCodeRequest(@NotBlank String code, @NotBlank String deviceName,
                                         @NotBlank String platform, @NotBlank String arch,
                                         @NotBlank String workerVersion, @NotBlank String protocolVersion) {
    }
}
