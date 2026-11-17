package com.company.aitest.dsh;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dsh/user/projects/{projectId}/captures")
public class DshCaptureUserController {
    private final DshCaptureService captures;
    private final DshCaptureBundleService bundles;

    public DshCaptureUserController(DshCaptureService captures, DshCaptureBundleService bundles) {
        this.captures = captures;
        this.bundles = bundles;
    }

    @GetMapping("/{sessionId}/bundle/summary")
    public ApiResponse<DshCaptureBundleService.Bundle> bundleSummary(@PathVariable Long projectId,
            @PathVariable Long sessionId, Authentication auth) {
        captures.run(projectId, sessionId, user(auth));
        return ApiResponse.ok(bundles.get(projectId, sessionId, user(auth)));
    }

    @GetMapping("/{sessionId}/bundle")
    public ResponseEntity<Resource> bundle(@PathVariable Long projectId, @PathVariable Long sessionId,
            Authentication auth) {
        captures.run(projectId, sessionId, user(auth));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=capture-" + sessionId + ".tar.gz")
                .body(new FileSystemResource(bundles.file(projectId, sessionId, user(auth))));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<DshCaptureService.CaptureRun> run(@PathVariable Long projectId,
                                                           @PathVariable Long sessionId, Authentication auth) {
        return ApiResponse.ok(captures.run(projectId, sessionId, user(auth)));
    }

    @GetMapping("/{sessionId}/artifacts")
    public ApiResponse<List<DshCaptureService.Evidence>> evidence(@PathVariable Long projectId,
                                                                    @PathVariable Long sessionId, Authentication auth) {
        return ApiResponse.ok(captures.evidence(projectId, sessionId, user(auth)));
    }

    @GetMapping("/{sessionId}/artifacts/{artifactId}")
    public ResponseEntity<Resource> artifact(@PathVariable Long projectId, @PathVariable Long sessionId,
                                              @PathVariable Long artifactId, Authentication auth) {
        var stored = captures.stored(projectId, sessionId, artifactId, user(auth));
        MediaType type = switch (stored.kind()) {
            case "FRAME" -> MediaType.IMAGE_JPEG;
            case "SCREENSHOT" -> stored.fileName().toLowerCase().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
            case "MANIFEST" -> MediaType.APPLICATION_JSON;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(stored.path()));
    }

    private static CurrentUser user(Authentication auth) {
        return ((DshConnectionService.DshPrincipal) auth.getPrincipal()).user();
    }
}
