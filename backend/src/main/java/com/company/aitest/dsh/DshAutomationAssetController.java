package com.company.aitest.dsh;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dsh/tools/projects/{projectId}/automation-assets")
public class DshAutomationAssetController {
    private final AutomationAssetService assets;

    public DshAutomationAssetController(AutomationAssetService assets) { this.assets = assets; }

    @GetMapping
    public ApiResponse<List<AutomationAssetService.Asset>> list(@PathVariable Long projectId, Authentication auth) {
        return ApiResponse.ok(assets.list(projectId, user(auth)));
    }

    @PostMapping
    public ApiResponse<AutomationAssetService.Asset> create(@PathVariable Long projectId,
            @RequestBody AutomationAssetService.CreateInput input, Authentication auth) {
        return ApiResponse.ok(assets.create(projectId, input, user(auth)));
    }

    @GetMapping("/{assetId}")
    public ApiResponse<AutomationAssetService.Asset> get(@PathVariable Long projectId, @PathVariable Long assetId,
            Authentication auth) {
        return ApiResponse.ok(assets.get(projectId, assetId, user(auth)));
    }

    @GetMapping("/{assetId}/versions/{versionNo}")
    public ApiResponse<AutomationAssetService.Version> version(@PathVariable Long projectId, @PathVariable Long assetId,
            @PathVariable int versionNo, Authentication auth) {
        return ApiResponse.ok(assets.version(projectId, assetId, versionNo, user(auth)));
    }

    @PostMapping("/{assetId}/runs")
    public ApiResponse<AutomationAssetService.Run> run(@PathVariable Long projectId, @PathVariable Long assetId,
            @RequestBody AutomationAssetService.RunInput input, Authentication auth) {
        return ApiResponse.ok(assets.recordRun(projectId, assetId, input, user(auth)));
    }

    @GetMapping("/{assetId}/runs")
    public ApiResponse<List<AutomationAssetService.Run>> runs(@PathVariable Long projectId, @PathVariable Long assetId,
            Authentication auth) {
        return ApiResponse.ok(assets.runs(projectId, assetId, user(auth)));
    }

    @PostMapping("/{assetId}/api-evidence")
    public ApiResponse<AutomationAssetService.ApiEvidence> apiEvidence(@PathVariable Long projectId,
            @PathVariable Long assetId, @RequestBody AutomationAssetService.ApiEvidenceInput input,
            Authentication auth) {
        return ApiResponse.ok(assets.recordApiEvidence(projectId, assetId, input, user(auth)));
    }

    @GetMapping("/{assetId}/api-evidence/{evidenceId}")
    public ApiResponse<AutomationAssetService.ApiEvidence> getApiEvidence(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable Long evidenceId, Authentication auth) {
        return ApiResponse.ok(assets.apiEvidence(projectId, assetId, evidenceId, user(auth)));
    }

    @PostMapping("/{assetId}/heals")
    public ApiResponse<AutomationAssetService.Version> heal(@PathVariable Long projectId, @PathVariable Long assetId,
            @RequestBody AutomationAssetService.HealInput input, Authentication auth) {
        return ApiResponse.ok(assets.proposeHeal(projectId, assetId, input, user(auth)));
    }

    @PostMapping("/{assetId}/versions")
    public ApiResponse<AutomationAssetService.Version> revise(@PathVariable Long projectId, @PathVariable Long assetId,
            @RequestBody AutomationAssetService.ManualRevisionInput input, Authentication auth) {
        return ApiResponse.ok(assets.proposeManualVersion(projectId, assetId, input, user(auth)));
    }

    @PostMapping("/{assetId}/archive")
    public ApiResponse<AutomationAssetService.Asset> archive(@PathVariable Long projectId, @PathVariable Long assetId,
            @RequestBody AutomationAssetService.DispositionInput input, Authentication auth) {
        return ApiResponse.ok(assets.archivePersonal(projectId, assetId, input, user(auth)));
    }

    @PostMapping("/{assetId}/versions/{versionNo}/discard")
    public ApiResponse<AutomationAssetService.Version> discardCandidate(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable int versionNo,
            @RequestBody AutomationAssetService.DispositionInput input, Authentication auth) {
        return ApiResponse.ok(assets.discardPersonalCandidate(projectId, assetId, versionNo, input, user(auth)));
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }
}

@RestController
@RequestMapping("/api/dsh/tools/automation-publications")
class DshAutomationPublicationController {
    private final AutomationAssetService assets;

    DshAutomationPublicationController(AutomationAssetService assets) { this.assets = assets; }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(defaultValue = "") String query,
            Authentication auth) {
        return ApiResponse.ok(assets.reusable(query, user(auth)));
    }

    @GetMapping("/{publicationId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long publicationId, Authentication auth) {
        return ApiResponse.ok(assets.reusableVersion(publicationId, user(auth)));
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }
}
