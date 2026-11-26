package com.company.aitest.dsh;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/aitestwork/dsh/tools/projects/{projectId}/ui-coverage")
public class UiCoverageController {
    private final UiCoverageService service;

    public UiCoverageController(UiCoverageService service) { this.service = service; }

    @GetMapping
    public ApiResponse<java.util.List<UiCoverageService.Verdict>> list(@PathVariable Long projectId,
            Authentication auth) {
        return ApiResponse.ok(service.list(projectId, user(auth)));
    }

    @PostMapping
    public ApiResponse<UiCoverageService.Verdict> start(@PathVariable Long projectId,
            @RequestBody UiCoverageService.TaskInput input, Authentication auth) {
        return ApiResponse.ok(service.start(projectId, input, user(auth)));
    }

    @PostMapping("/{taskId}/obligations")
    public ApiResponse<UiCoverageService.Verdict> add(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody UiCoverageService.ObligationInput input, Authentication auth) {
        return ApiResponse.ok(service.add(projectId, taskId, input, user(auth)));
    }

    @GetMapping("/rules")
    public ApiResponse<java.util.List<UiCoverageService.Rule>> rules(@PathVariable Long projectId,
            Authentication auth) {
        return ApiResponse.ok(service.effectiveRules(projectId, user(auth)));
    }

    @PostMapping("/rules")
    public ApiResponse<UiCoverageService.Rule> proposeRule(@PathVariable Long projectId,
            @RequestBody UiCoverageService.RuleInput input, Authentication auth) {
        return ApiResponse.ok(service.proposeRule(projectId, input, user(auth)));
    }

    @PostMapping("/{taskId}/rules/{ruleCode}")
    public ApiResponse<UiCoverageService.Verdict> decideRule(@PathVariable Long projectId,
            @PathVariable Long taskId, @PathVariable String ruleCode,
            @RequestBody UiCoverageService.RuleDecision input, Authentication auth) {
        return ApiResponse.ok(service.decideRule(projectId, taskId, ruleCode, input, user(auth)));
    }

    @PostMapping("/{taskId}/obligations/{obligationId}/evidence")
    public ApiResponse<UiCoverageService.Verdict> attach(@PathVariable Long projectId, @PathVariable Long taskId,
            @PathVariable Long obligationId, @RequestBody UiCoverageService.EvidenceInput input, Authentication auth) {
        return ApiResponse.ok(service.attach(projectId, taskId, obligationId, input, user(auth)));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<UiCoverageService.Verdict> verdict(@PathVariable Long projectId, @PathVariable Long taskId,
            Authentication auth) {
        return ApiResponse.ok(service.verdict(projectId, taskId, user(auth)));
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }
}

@RestController
@RequestMapping("/api/projects/{projectId}/ui-coverage")
class UiCoverageWebController {
    private final UiCoverageService service;

    UiCoverageWebController(UiCoverageService service) { this.service = service; }

    @GetMapping
    public ApiResponse<java.util.List<UiCoverageService.Verdict>> list(@PathVariable Long projectId,
            Authentication auth) {
        return ApiResponse.ok(service.list(projectId, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<UiCoverageService.Verdict> verdict(@PathVariable Long projectId, @PathVariable Long taskId,
            Authentication auth) {
        return ApiResponse.ok(service.verdict(projectId, taskId, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/rules/pending")
    public ApiResponse<java.util.List<UiCoverageService.Rule>> pendingRules(@PathVariable Long projectId,
            Authentication auth) {
        return ApiResponse.ok(service.pendingRules(projectId, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/rules/{ruleId}/approve")
    public ApiResponse<UiCoverageService.Rule> approveRule(@PathVariable Long projectId, @PathVariable Long ruleId,
            Authentication auth) {
        return ApiResponse.ok(service.approveRule(projectId, ruleId, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{taskId}/confirm")
    public ApiResponse<UiCoverageService.Verdict> confirm(@PathVariable Long projectId, @PathVariable Long taskId,
            Authentication auth) {
        return ApiResponse.ok(service.confirm(projectId, taskId, (CurrentUser) auth.getPrincipal()));
    }
}

@RestController
@RequestMapping("/api/aitestwork/dsh/user/projects/{projectId}/ui-coverage")
class UiCoverageUserController {
    private final UiCoverageService service;

    UiCoverageUserController(UiCoverageService service) { this.service = service; }

    @PostMapping("/{taskId}/confirm")
    public ApiResponse<UiCoverageService.Verdict> confirm(@PathVariable Long projectId, @PathVariable Long taskId,
            Authentication auth) {
        CurrentUser user = ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
        return ApiResponse.ok(service.confirm(projectId, taskId, user));
    }
}
