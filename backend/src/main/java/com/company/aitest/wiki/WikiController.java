package com.company.aitest.wiki;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.BusinessException;
import com.company.aitest.audit.OperationLogService;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    private final WikiService wikiService;
    private final ProjectAccessService projectAccessService;
    private final OperationLogService operationLogService;

    public WikiController(WikiService wikiService, ProjectAccessService projectAccessService,
                          OperationLogService operationLogService) {
        this.wikiService = wikiService;
        this.projectAccessService = projectAccessService;
        this.operationLogService = operationLogService;
    }

    // ---- Pack endpoints ----

    @GetMapping("/packs")
    public ApiResponse<List<WikiPackRecord>> listPacks(@RequestParam Long projectId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        return ApiResponse.ok(wikiService.listPacks(projectId));
    }

    @GetMapping("/admin/packs")
    public ApiResponse<List<WikiPackRecord>> listPacksForAdmin(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reviewStatus,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensurePlatformAdmin(user);
        return ApiResponse.ok(wikiService.listPacksForAdmin(projectId, scope, status, reviewStatus));
    }

    @GetMapping("/packs/{packId}")
    public ApiResponse<WikiPackRecord> getPack(@PathVariable Long packId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        WikiPackRecord pack = wikiService.getPack(packId);
        projectAccessService.ensureCanAccess(pack.projectId(), user);
        return ApiResponse.ok(pack);
    }

    @PostMapping("/packs")
    public ApiResponse<WikiPackRecord> createPack(@RequestBody Map<String, Object> body,
                                                    Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        Long projectId = ((Number) body.get("projectId")).longValue();
        String scope = (String) body.getOrDefault("scope", "PROJECT");
        String name = (String) body.get("name");
        String description = (String) body.getOrDefault("description", "");
        if (name == null || name.isBlank()) throw new BusinessException("名称不能为空");
        if ("REUSABLE".equals(scope) || "SYSTEM".equals(scope)) {
            projectAccessService.ensurePlatformAdmin(user);
        } else {
            projectAccessService.ensureCanAccess(projectId, user);
        }
        WikiPackRecord pack = wikiService.createPack(projectId, scope, name, description, user);
        operationLogService.recordQuietly(user.id(), "WIKI_CREATE_PACK", "WIKI_PACK", pack.id(),
                "{\"projectId\":" + projectId + ",\"scope\":\"" + safe(scope) + "\"}");
        return ApiResponse.ok(pack);
    }

    @PatchMapping("/packs/{packId}/status")
    public ApiResponse<WikiPackRecord> updatePackStatus(@PathVariable Long packId,
                                                          @RequestBody Map<String, String> body,
                                                          Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String status = body.get("status");
        if (status == null) throw new BusinessException("status 不能为空");
        WikiPackRecord pack = wikiService.getPack(packId);
        ensureCanManagePack(pack, user);
        WikiPackRecord updated = wikiService.updatePackStatus(packId, status, user);
        operationLogService.recordQuietly(user.id(), "WIKI_UPDATE_PACK_STATUS", "WIKI_PACK", packId,
                "{\"status\":\"" + safe(status) + "\"}");
        return ApiResponse.ok(updated);
    }

    @PatchMapping("/packs/{packId}/review")
    public ApiResponse<WikiPackRecord> reviewPack(@PathVariable Long packId,
                                                   @RequestBody Map<String, String> body,
                                                   Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String reviewStatus = body.get("reviewStatus");
        if (reviewStatus == null) throw new BusinessException("reviewStatus 不能为空");
        WikiPackRecord pack = wikiService.getPack(packId);
        ensureCanManagePack(pack, user);
        WikiPackRecord updated = wikiService.reviewPack(packId, reviewStatus, user);
        operationLogService.recordQuietly(user.id(), "WIKI_REVIEW_PACK", "WIKI_PACK", packId,
                "{\"reviewStatus\":\"" + safe(reviewStatus) + "\"}");
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/packs/{packId}")
    public ApiResponse<Void> deletePack(@PathVariable Long packId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        WikiPackRecord pack = wikiService.getPack(packId);
        ensureCanManagePack(pack, user);
        wikiService.deletePack(packId);
        operationLogService.recordQuietly(user.id(), "WIKI_DELETE_PACK", "WIKI_PACK", packId,
                "{\"projectId\":" + pack.projectId() + ",\"scope\":\"" + safe(pack.scope()) + "\"}");
        return ApiResponse.ok(null);
    }

    // ---- Entry endpoints ----

    @GetMapping("/packs/{packId}/entries")
    public ApiResponse<List<WikiEntryRecord>> listEntries(@PathVariable Long packId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        projectAccessService.ensureCanAccess(wikiService.projectIdForPack(packId), user);
        return ApiResponse.ok(wikiService.listEntries(packId));
    }

    @PostMapping("/packs/{packId}/entries")
    public ApiResponse<WikiEntryRecord> createEntry(@PathVariable Long packId,
                                                     @RequestBody Map<String, Object> body,
                                                     Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String entryType = (String) body.get("entryType");
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        if (entryType == null || title == null || content == null) {
            throw new BusinessException("entryType, title, content 不能为空");
        }
        WikiPackRecord pack = wikiService.getPack(packId);
        ensureCanManagePack(pack, user);
        String keywordsJson = (String) body.get("keywordsJson");
        String sourceRefsJson = (String) body.get("sourceRefsJson");
        WikiEntryRecord entry = wikiService.createEntry(packId, entryType, title, content, keywordsJson, sourceRefsJson, user);
        operationLogService.recordQuietly(user.id(), "WIKI_CREATE_ENTRY", "WIKI_ENTRY", entry.id(),
                "{\"packId\":" + packId + ",\"entryType\":\"" + safe(entryType) + "\"}");
        return ApiResponse.ok(entry);
    }

    @PatchMapping("/entries/{entryId}/review")
    public ApiResponse<WikiEntryRecord> reviewEntry(@PathVariable Long entryId,
                                                      @RequestBody Map<String, String> body,
                                                      Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String reviewStatus = body.get("reviewStatus");
        if (reviewStatus == null) throw new BusinessException("reviewStatus 不能为空");
        WikiPackRecord pack = wikiService.getPackForEntry(entryId);
        ensureCanManagePack(pack, user);
        WikiEntryRecord entry = wikiService.reviewEntry(entryId, reviewStatus, user);
        operationLogService.recordQuietly(user.id(), "WIKI_REVIEW_ENTRY", "WIKI_ENTRY", entryId,
                "{\"reviewStatus\":\"" + safe(reviewStatus) + "\"}");
        return ApiResponse.ok(entry);
    }

    private void ensureCanManagePack(WikiPackRecord pack, CurrentUser user) {
        if ("REUSABLE".equals(pack.scope()) || "SYSTEM".equals(pack.scope())) {
            projectAccessService.ensurePlatformAdmin(user);
        } else {
            projectAccessService.ensureCanManageProject(pack.projectId(), user);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
