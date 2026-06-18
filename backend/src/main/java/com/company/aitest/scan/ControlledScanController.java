package com.company.aitest.scan;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/controlled-scans")
public class ControlledScanController {
    private final ControlledScanService controlledScanService;

    public ControlledScanController(ControlledScanService controlledScanService) {
        this.controlledScanService = controlledScanService;
    }

    @GetMapping("/skills")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<ControlledSkillDefinitionView>> listBuiltinSkills() {
        return ApiResponse.ok(controlledScanService.listBuiltinSkills());
    }

    @GetMapping("/sources")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<ControlledScanService.BuiltinScanSourceOptionView>> listBuiltinSources() {
        return ApiResponse.ok(controlledScanService.listBuiltinSourceOptions());
    }

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<ControlledScanJobRecord> run(@Valid @RequestBody RunControlledScanRequest request,
                                                    Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(controlledScanService.runScan(
                request.projectId(),
                new ControlledScanService.RunControlledScanCommand(
                        request.modelConfigId(),
                        request.scanMode(),
                        request.sourceKeys(),
                        request.urls()),
                user));
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<ControlledScanJobRecord>> listJobs(@RequestParam Long projectId) {
        return ApiResponse.ok(controlledScanService.listJobs(projectId));
    }

    @GetMapping("/profiles")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<PageScanProfileRecord>> listProfiles(@RequestParam Long projectId) {
        return ApiResponse.ok(controlledScanService.listProfiles(projectId));
    }

    public record RunControlledScanRequest(
            @NotNull Long projectId,
            Long modelConfigId,
            String scanMode,
            List<String> sourceKeys,
            List<String> urls) {
    }
}
