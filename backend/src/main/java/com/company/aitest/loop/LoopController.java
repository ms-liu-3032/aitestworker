package com.company.aitest.loop;

import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loop")
public class LoopController {

    private final LoopService loopService;

    public LoopController(LoopService loopService) {
        this.loopService = loopService;
    }

    @GetMapping("/status")
    public ApiResponse<Boolean> getStatus() {
        return ApiResponse.ok(loopService.isLoopEnabled());
    }

    @PostMapping("/status")
    public ApiResponse<Void> setStatus(@RequestBody Map<String, Boolean> body, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        Boolean enabled = body.get("enabled");
        if (enabled == null) throw new BusinessException("enabled 不能为空");
        loopService.setLoopEnabled(enabled, user);
        return ApiResponse.ok(null);
    }

    @GetMapping("/events")
    public ApiResponse<?> listEvents(@RequestParam Long projectId) {
        return ApiResponse.ok(loopService.listEvents(projectId));
    }

    @PostMapping("/events")
    public ApiResponse<LoopEventRecord> recordEvent(@RequestBody Map<String, Object> body,
                                                     @RequestParam Long projectId,
                                                     Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String eventType = (String) body.get("eventType");
        String sourceStage = (String) body.get("sourceStage");
        String rawInput = (String) body.get("rawInput");
        String normalizedIssue = (String) body.get("normalizedIssue");
        String suggestedAssetType = (String) body.get("suggestedAssetType");
        String sourceRefsJson = (String) body.get("sourceRefsJson");
        if (eventType == null) throw new BusinessException("eventType 不能为空");
        return ApiResponse.ok(loopService.recordEvent(projectId, eventType, sourceStage,
                rawInput, normalizedIssue, suggestedAssetType, sourceRefsJson, user));
    }

    @GetMapping("/clusters")
    public ApiResponse<?> listClusters(@RequestParam Long projectId) {
        return ApiResponse.ok(loopService.listClusters(projectId));
    }

    @PostMapping("/clusters")
    public ApiResponse<LoopClusterRecord> createCluster(@RequestBody Map<String, Object> body,
                                                         @RequestParam Long projectId,
                                                         Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String theme = (String) body.get("theme");
        String suggestedAction = (String) body.get("suggestedAction");
        String targetAssetType = (String) body.get("targetAssetType");
        return ApiResponse.ok(loopService.createCluster(projectId, theme, suggestedAction, targetAssetType, user));
    }

    @PatchMapping("/clusters/{clusterId}/status")
    public ApiResponse<LoopClusterRecord> updateClusterStatus(@PathVariable Long clusterId,
                                                                @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null) throw new BusinessException("status 不能为空");
        return ApiResponse.ok(loopService.updateClusterStatus(clusterId, status));
    }

    @PatchMapping("/events/{eventId}/status")
    public ApiResponse<LoopEventRecord> updateEventStatus(@PathVariable Long eventId,
                                                           @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null) throw new BusinessException("status 不能为空");
        return ApiResponse.ok(loopService.updateEventStatus(eventId, status));
    }

    @PostMapping("/consume")
    public ApiResponse<Map<String, Object>> consume(@RequestParam Long projectId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        int candidates = loopService.generateCandidates(projectId);
        return ApiResponse.ok(Map.of("candidatesGenerated", candidates));
    }
}
