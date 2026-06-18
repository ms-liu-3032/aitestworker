package com.company.aitest.trace;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/trace-rule-packs")
public class TraceRulePackConfigController {
    private final TraceRulePackConfigService service;

    public TraceRulePackConfigController(TraceRulePackConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<TraceRulePackConfigService.TraceRulePackConfigRecord> listPacks(@PathVariable Long projectId) {
        return service.listPacks(projectId);
    }

    @GetMapping("/{packId}")
    public TraceRulePackConfigService.TraceRulePackConfigRecord getPack(@PathVariable Long packId) {
        return service.getPack(packId);
    }

    @PostMapping
    public TraceRulePackConfigService.TraceRulePackConfigRecord createPack(
            @PathVariable Long projectId,
            @RequestBody TraceRulePackConfigService.CreateRulePackCommand command,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return service.createPack(projectId, command, user);
    }

    @PatchMapping("/{packId}")
    public TraceRulePackConfigService.TraceRulePackConfigRecord updatePack(
            @PathVariable Long projectId,
            @PathVariable Long packId,
            @RequestBody TraceRulePackConfigService.UpdateRulePackCommand command,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return service.updatePack(projectId, packId, command, user);
    }

    @PostMapping("/{packId}/activate")
    public void activate(@PathVariable Long projectId, @PathVariable Long packId) {
        service.activate(projectId, packId);
    }

    @PostMapping("/{packId}/deactivate")
    public void deactivate(@PathVariable Long projectId, @PathVariable Long packId) {
        service.deactivate(projectId, packId);
    }

    @PostMapping("/{packId}/archive")
    public void archive(@PathVariable Long projectId, @PathVariable Long packId) {
        service.archive(projectId, packId);
    }

    @DeleteMapping("/{packId}")
    public void deletePack(@PathVariable Long projectId, @PathVariable Long packId) {
        service.deletePack(projectId, packId);
    }
}
