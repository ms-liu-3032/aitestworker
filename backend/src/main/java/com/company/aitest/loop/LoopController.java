package com.company.aitest.loop;

import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.audit.OperationLogService;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loop")
public class LoopController {

    private final LoopService loopService;
    private final ProjectAccessService projectAccessService;
    private final OperationLogService operationLogService;

    public LoopController(LoopService loopService, ProjectAccessService projectAccessService,
                          OperationLogService operationLogService) {
        this.loopService = loopService;
        this.projectAccessService = projectAccessService;
        this.operationLogService = operationLogService;
    }

    @GetMapping("/status")
    public ApiResponse<Boolean> getStatus() {
        return ApiResponse.ok(loopService.isLoopEnabled());
    }

    @PostMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<Void> setStatus(@RequestBody Map<String, Boolean> body, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        Boolean enabled = body.get("enabled");
        if (enabled == null) throw new BusinessException("enabled 不能为空");
        loopService.setLoopEnabled(enabled, user);
        operationLogService.recordQuietly(user.id(), "LOOP_SET_STATUS", "LOOP_ENGINE", null,
                "{\"enabled\":" + enabled + "}");
        return ApiResponse.ok(null);
    }

    @GetMapping("/events")
    public ApiResponse<?> listEvents(@RequestParam Long projectId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(loopService.listEvents(projectId));
    }

    @PostMapping("/events")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<LoopEventRecord> recordEvent(@RequestBody Map<String, Object> body,
                                                     @RequestParam Long projectId,
                                                     Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        String eventType = (String) body.get("eventType");
        String sourceStage = (String) body.get("sourceStage");
        String rawInput = (String) body.get("rawInput");
        String normalizedIssue = (String) body.get("normalizedIssue");
        String suggestedAssetType = (String) body.get("suggestedAssetType");
        String sourceRefsJson = (String) body.get("sourceRefsJson");
        if (eventType == null) throw new BusinessException("eventType 不能为空");
        LoopEventRecord event = loopService.recordEvent(projectId, eventType, sourceStage,
                rawInput, normalizedIssue, suggestedAssetType, sourceRefsJson, user);
        operationLogService.recordQuietly(user.id(), "LOOP_RECORD_EVENT", "LOOP_EVENT", event.id(),
                "{\"projectId\":" + projectId + ",\"eventType\":\"" + safe(eventType) + "\"}");
        return ApiResponse.ok(event);
    }

    @GetMapping("/clusters")
    public ApiResponse<?> listClusters(@RequestParam Long projectId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(loopService.listClusters(projectId));
    }

    @PostMapping("/clusters")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<LoopClusterRecord> createCluster(@RequestBody Map<String, Object> body,
                                                         @RequestParam Long projectId,
                                                         Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        String theme = (String) body.get("theme");
        String suggestedAction = (String) body.get("suggestedAction");
        String targetAssetType = (String) body.get("targetAssetType");
        LoopClusterRecord cluster = loopService.createCluster(projectId, theme, suggestedAction, targetAssetType, user);
        operationLogService.recordQuietly(user.id(), "LOOP_CREATE_CLUSTER", "LOOP_CLUSTER", cluster.id(),
                "{\"projectId\":" + projectId + ",\"targetAssetType\":\"" + safe(targetAssetType) + "\"}");
        return ApiResponse.ok(cluster);
    }

    @PatchMapping("/clusters/{clusterId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<LoopClusterRecord> updateClusterStatus(@PathVariable Long clusterId,
                                                                @RequestBody Map<String, String> body,
                                                                Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanManageProject(loopService.getCluster(clusterId).projectId(), user);
        String status = body.get("status");
        if (status == null) throw new BusinessException("status 不能为空");
        LoopClusterRecord cluster = loopService.updateClusterStatus(clusterId, status);
        operationLogService.recordQuietly(user.id(), "LOOP_UPDATE_CLUSTER_STATUS", "LOOP_CLUSTER", clusterId,
                "{\"status\":\"" + safe(status) + "\"}");
        return ApiResponse.ok(cluster);
    }

    @PatchMapping("/events/{eventId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<LoopEventRecord> updateEventStatus(@PathVariable Long eventId,
                                                           @RequestBody Map<String, String> body,
                                                           Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanManageProject(loopService.getEvent(eventId).projectId(), user);
        String status = body.get("status");
        if (status == null) throw new BusinessException("status 不能为空");
        LoopEventRecord event = loopService.updateEventStatus(eventId, status);
        operationLogService.recordQuietly(user.id(), "LOOP_UPDATE_EVENT_STATUS", "LOOP_EVENT", eventId,
                "{\"status\":\"" + safe(status) + "\"}");
        return ApiResponse.ok(event);
    }

    @PostMapping("/consume")
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<Map<String, Object>> consume(@RequestParam Long projectId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanManageProject(projectId, user);
        int candidates = loopService.generateCandidates(projectId);
        operationLogService.recordQuietly(user.id(), "LOOP_CONSUME_CANDIDATES", "PROJECT", projectId,
                "{\"candidatesGenerated\":" + candidates + "}");
        return ApiResponse.ok(Map.of("candidatesGenerated", candidates));
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
