package com.company.aitest.scan;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/scan-sources")
public class ScanSourceConfigController {

    private final ScanSourceConfigService scanSourceConfigService;

    public ScanSourceConfigController(ScanSourceConfigService scanSourceConfigService) {
        this.scanSourceConfigService = scanSourceConfigService;
    }

    @GetMapping
    public List<ScanSourceConfigService.ScanSourceConfigRecord> listSources(@PathVariable Long projectId) {
        return scanSourceConfigService.listSources(projectId);
    }

    @GetMapping("/defaults")
    public List<ScanSourceConfigService.ScanSourceConfigRecord> listDefaultSources(@PathVariable Long projectId) {
        return scanSourceConfigService.listDefaultSources(projectId);
    }

    @GetMapping("/{sourceId}")
    public ScanSourceConfigService.ScanSourceConfigRecord getSource(@PathVariable Long projectId,
                                                                    @PathVariable Long sourceId) {
        return scanSourceConfigService.getSource(projectId, sourceId);
    }

    @PostMapping
    public ScanSourceConfigService.ScanSourceConfigRecord createSource(
            @PathVariable Long projectId,
            @RequestBody ScanSourceConfigService.CreateSourceCommand command,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        // 强制设置 projectId
        var cmd = new ScanSourceConfigService.CreateSourceCommand(
                projectId, command.sourceKey(), command.sourceLabel(), command.sourceType(),
                command.sourceUrl(), command.sourceFilePath(), command.defaultSelected(),
                command.enabled(), command.description(), command.configJson());
        return scanSourceConfigService.createSource(cmd, user);
    }

    @PatchMapping("/{sourceId}")
    public ScanSourceConfigService.ScanSourceConfigRecord updateSource(
            @PathVariable Long projectId,
            @PathVariable Long sourceId,
            @RequestBody ScanSourceConfigService.UpdateSourceCommand command,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return scanSourceConfigService.updateSource(projectId, sourceId, command, user);
    }

    @DeleteMapping("/{sourceId}")
    public void deleteSource(@PathVariable Long projectId, @PathVariable Long sourceId) {
        scanSourceConfigService.deleteSource(projectId, sourceId);
    }

    @PostMapping("/{sourceId}/enable")
    public void enableSource(@PathVariable Long projectId, @PathVariable Long sourceId) {
        scanSourceConfigService.enableSource(projectId, sourceId);
    }

    @PostMapping("/{sourceId}/disable")
    public void disableSource(@PathVariable Long projectId, @PathVariable Long sourceId) {
        scanSourceConfigService.disableSource(projectId, sourceId);
    }
}
