package com.company.aitest.dsh;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/dsh/tools/projects/{projectId}/captures")
public class DshCaptureToolController {
    private final DshCaptureService captures;
    private final DshCaptureBundleService bundles;

    public DshCaptureToolController(DshCaptureService captures, DshCaptureBundleService bundles) {
        this.captures = captures;
        this.bundles = bundles;
    }

    @PostMapping
    public ApiResponse<DshCaptureService.CaptureStart> start(@PathVariable Long projectId,
                                                               @RequestBody DshCaptureService.StartRequest request,
                                                               Authentication auth) {
        return ApiResponse.ok(captures.start(projectId, request, user(auth)));
    }

    @PostMapping("/{sessionId}/artifacts/{kind}")
    public ApiResponse<DshCaptureService.Evidence> upload(@PathVariable Long projectId,
                                                            @PathVariable Long sessionId,
                                                            @PathVariable String kind,
                                                            @RequestParam("file") MultipartFile file,
                                                            Authentication auth) {
        return ApiResponse.ok(captures.upload(projectId, sessionId, kind, file, user(auth)));
    }

    @PostMapping("/{sessionId}/bundle/chunks/{requestKey}/{index}")
    public ApiResponse<Void> bundleChunk(@PathVariable Long projectId, @PathVariable Long sessionId,
            @PathVariable String requestKey, @PathVariable int index, @RequestBody byte[] bytes, Authentication auth) {
        bundles.chunk(projectId, sessionId, requestKey, index, bytes, user(auth));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{sessionId}/bundle/commit")
    public ApiResponse<DshCaptureBundleService.Bundle> bundleCommit(@PathVariable Long projectId,
            @PathVariable Long sessionId, @RequestBody DshCaptureBundleService.CommitRequest request,
            Authentication auth) {
        return ApiResponse.ok(bundles.commit(projectId, sessionId, request, user(auth)));
    }

    @PostMapping("/{sessionId}/finish")
    public ApiResponse<DshCaptureService.CaptureFinished> finish(@PathVariable Long projectId,
                                                                    @PathVariable Long sessionId,
                                                                    @RequestBody DshCaptureService.FinishRequest request,
                                                                    Authentication auth) {
        return ApiResponse.ok(captures.finish(projectId, sessionId, request, user(auth)));
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }
}
