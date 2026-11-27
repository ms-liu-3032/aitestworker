package com.company.aitest.dsh;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.PageResult;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/permissions")
    public ApiResponse<Map<String, Boolean>> permissions(@PathVariable Long projectId, Authentication auth) {
        return ApiResponse.ok(assets.permissions(projectId, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<AutomationAssetService.Asset>> page(@PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "ALL") String scope, @RequestParam(defaultValue = "ALL") String type,
            Authentication auth) {
        return ApiResponse.ok(assets.page(projectId, page, size, scope, type,
                (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/{assetId}")
    public ApiResponse<AutomationAssetService.Asset> get(@PathVariable Long projectId, @PathVariable Long assetId,
            Authentication auth) {
        return ApiResponse.ok(assets.get(projectId, assetId, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/{assetId}/publications")
    public ApiResponse<List<AutomationAssetService.Publication>> publications(@PathVariable Long projectId,
            @PathVariable Long assetId, Authentication auth) {
        return ApiResponse.ok(assets.publications(projectId, assetId, (CurrentUser) auth.getPrincipal()));
    }

    @GetMapping("/{assetId}/publications/{publicationId}/adoptions")
    public ApiResponse<List<AutomationAssetService.Adoption>> adoptions(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable Long publicationId, Authentication auth) {
        return ApiResponse.ok(assets.adoptions(projectId, assetId, publicationId,
                (CurrentUser) auth.getPrincipal()));
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

    @PostMapping("/{assetId}/versions/{versionNo}/publish-reusable")
    public ApiResponse<Long> publishVersion(@PathVariable Long projectId, @PathVariable Long assetId,
            @PathVariable int versionNo, Authentication auth) {
        return ApiResponse.ok(assets.publishReusableVersionBound(projectId, assetId, versionNo,
                (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/versions/{versionNo}/request-system")
    public ApiResponse<AutomationAssetService.Publication> requestSystem(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable int versionNo, Authentication auth) {
        return ApiResponse.ok(assets.requestSystem(projectId, assetId, versionNo, (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/publications/{publicationId}/approve-system")
    public ApiResponse<AutomationAssetService.Publication> approveSystem(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable Long publicationId, Authentication auth) {
        return ApiResponse.ok(assets.reviewSystem(projectId, assetId, publicationId, true, null,
                (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/publications/{publicationId}/reject-system")
    public ApiResponse<AutomationAssetService.Publication> rejectSystem(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable Long publicationId,
            @RequestBody AutomationAssetService.DispositionInput input, Authentication auth) {
        return ApiResponse.ok(assets.reviewSystem(projectId, assetId, publicationId, false, input,
                (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/{assetId}/publications/{publicationId}/withdraw")
    public ApiResponse<AutomationAssetService.Publication> withdraw(@PathVariable Long projectId,
            @PathVariable Long assetId, @PathVariable Long publicationId,
            @RequestBody AutomationAssetService.DispositionInput input, Authentication auth) {
        return ApiResponse.ok(assets.withdrawPublication(projectId, assetId, publicationId, input,
                (CurrentUser) auth.getPrincipal()));
    }

    @PostMapping("/batch-publish-reusable")
    public ApiResponse<List<AutomationAssetService.PublicationResult>> batchPublish(@PathVariable Long projectId,
            @RequestBody AutomationAssetService.BatchPublicationInput input, Authentication auth) {
        return ApiResponse.ok(assets.publishReusableBatch(projectId, input, (CurrentUser) auth.getPrincipal()));
    }
}
