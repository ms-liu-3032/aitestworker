package com.company.aitest.wiki;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    private final WikiService wikiService;

    public WikiController(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    // ---- Pack endpoints ----

    @GetMapping("/packs")
    public ApiResponse<List<WikiPackRecord>> listPacks(@RequestParam Long projectId) {
        return ApiResponse.ok(wikiService.listPacks(projectId));
    }

    @GetMapping("/packs/{packId}")
    public ApiResponse<WikiPackRecord> getPack(@PathVariable Long packId) {
        return ApiResponse.ok(wikiService.getPack(packId));
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
        return ApiResponse.ok(wikiService.createPack(projectId, scope, name, description, user));
    }

    @PatchMapping("/packs/{packId}/status")
    public ApiResponse<WikiPackRecord> updatePackStatus(@PathVariable Long packId,
                                                          @RequestBody Map<String, String> body,
                                                          Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String status = body.get("status");
        if (status == null) throw new BusinessException("status 不能为空");
        return ApiResponse.ok(wikiService.updatePackStatus(packId, status, user));
    }

    @DeleteMapping("/packs/{packId}")
    public ApiResponse<Void> deletePack(@PathVariable Long packId) {
        wikiService.deletePack(packId);
        return ApiResponse.ok(null);
    }

    // ---- Entry endpoints ----

    @GetMapping("/packs/{packId}/entries")
    public ApiResponse<List<WikiEntryRecord>> listEntries(@PathVariable Long packId) {
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
        String keywordsJson = (String) body.get("keywordsJson");
        String sourceRefsJson = (String) body.get("sourceRefsJson");
        return ApiResponse.ok(wikiService.createEntry(packId, entryType, title, content, keywordsJson, sourceRefsJson, user));
    }

    @PatchMapping("/entries/{entryId}/review")
    public ApiResponse<WikiEntryRecord> reviewEntry(@PathVariable Long entryId,
                                                      @RequestBody Map<String, String> body,
                                                      Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        String reviewStatus = body.get("reviewStatus");
        if (reviewStatus == null) throw new BusinessException("reviewStatus 不能为空");
        return ApiResponse.ok(wikiService.reviewEntry(entryId, reviewStatus, user));
    }
}
