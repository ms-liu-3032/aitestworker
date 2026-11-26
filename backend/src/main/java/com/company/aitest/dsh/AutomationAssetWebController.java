package com.company.aitest.dsh;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Existing AITestWork Web users review blocked assets and publish a frozen verified version. */
@RestController
@RequestMapping("/api/projects/{projectId}/automation-assets")
public class AutomationAssetWebController {
    private final AutomationAssetService assets;

    public AutomationAssetWebController(AutomationAssetService assets) { this.assets = assets; }

    @GetMapping
    public ApiResponse<List<AutomationAssetService.Asset>> list(@PathVariable Long projectId, Authentication auth) {
        return ApiResponse.ok(assets.list(projectId, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/{assetId}/versions/{versionNo}")
    public ApiResponse<AutomationAssetService.Version> version(@PathVariable Long projectId, @PathVariable Long assetId,
            @PathVariable int versionNo, Authentication auth) {
        return ApiResponse.ok(assets.version(projectId, assetId, versionNo, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/{assetId}/runs")
    public ApiResponse<List<AutomationAssetService.Run>> runs(@PathVariable Long projectId, @PathVariable Long assetId,
            Authentication auth) {
        return ApiResponse.ok(assets.runs(projectId, assetId, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/{assetId}/api-evidence/{evidenceId}")
    public ApiResponse<AutomationAssetService.ApiEvidence> apiEvidence(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable Long evidenceId, Authentication auth) {
        return ApiResponse.ok(assets.apiEvidence(projectId, assetId, evidenceId, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/versions")
    public ApiResponse<AutomationAssetService.Version> revise(@PathVariable Long projectId, @PathVariable Long assetId,
            @org.springframework.web.bind.annotation.RequestBody AutomationAssetService.ManualRevisionInput input,
            Authentication auth) {
        return ApiResponse.ok(assets.proposeManualVersion(projectId, assetId, input, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/unblock")
    public ApiResponse<AutomationAssetService.Asset> unblock(@PathVariable Long projectId, @PathVariable Long assetId,
            Authentication auth) {
        return ApiResponse.ok(assets.unblock(projectId, assetId, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/versions/{versionNo}/approve")
    public ApiResponse<AutomationAssetService.Version> approve(@PathVariable Long projectId, @PathVariable Long assetId,
            @PathVariable int versionNo, Authentication auth) {
        return ApiResponse.ok(assets.approveVersion(projectId, assetId, versionNo, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/batch-approve")
    public ApiResponse<List<AutomationAssetService.Version>> batchApprove(@PathVariable Long projectId,
            @RequestBody AutomationAssetService.BatchApprovalInput input, Authentication auth) {
        return ApiResponse.ok(assets.approveVersions(projectId, input, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/versions/{versionNo}/discard")
    public ApiResponse<AutomationAssetService.Version> discardCandidate(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable int versionNo,
            @RequestBody AutomationAssetService.DispositionInput input, Authentication auth) {
        return ApiResponse.ok(assets.discardCandidate(projectId, assetId, versionNo, input,
                (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/archive")
    public ApiResponse<AutomationAssetService.Asset> archive(@PathVariable Long projectId, @PathVariable Long assetId,
            @RequestBody AutomationAssetService.DispositionInput input, Authentication auth) {
        return ApiResponse.ok(assets.archive(projectId, assetId, input, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/publish-reusable")
    public ApiResponse<Long> publish(@PathVariable Long projectId, @PathVariable Long assetId,
            Authentication auth) {
        return ApiResponse.ok(assets.publishReusable(projectId, assetId, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/batch-publish-reusable")
    public ApiResponse<List<AutomationAssetService.PublicationResult>> batchPublish(@PathVariable Long projectId,
            @RequestBody AutomationAssetService.BatchPublicationInput input, Authentication auth) {
        return ApiResponse.ok(assets.publishReusableBatch(projectId, input, (CurrentUser) auth.getPrincipal()));
    }
}
