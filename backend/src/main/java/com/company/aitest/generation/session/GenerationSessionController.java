package com.company.aitest.generation.session;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{projectId}/generation/sessions")
public class GenerationSessionController {

    private final GenerationSessionService sessionService;
    private final GenerationMessageService messageService;
    private final GenerationAttachmentService attachmentService;
    private final RequirementAnalysisService analysisService;
    private final ConversationOrchestrator orchestrator;
    private final JdbcClient jdbc;

    public GenerationSessionController(GenerationSessionService sessionService,
                                        GenerationMessageService messageService,
                                        GenerationAttachmentService attachmentService,
                                        RequirementAnalysisService analysisService,
                                        ConversationOrchestrator orchestrator,
                                        JdbcClient jdbc) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.analysisService = analysisService;
        this.orchestrator = orchestrator;
        this.jdbc = jdbc;
    }

    @PostMapping
    public ApiResponse<GenerationSessionRecord> create(@PathVariable Long projectId,
                                                        @RequestBody CreateSessionCommand cmd,
                                                        Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(sessionService.create(projectId, cmd, user));
    }

    @GetMapping
    public ApiResponse<PageResult<GenerationSessionRecord>> list(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(sessionService.list(projectId, page, size, status, keyword, user));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<GenerationSessionRecord> get(@PathVariable Long projectId, @PathVariable Long sessionId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return ApiResponse.ok(sessionService.get(projectId, sessionId, user));
    }

    @PatchMapping("/{sessionId}")
    public ApiResponse<Void> update(@PathVariable Long projectId, @PathVariable Long sessionId,
                                     @RequestBody Map<String, Object> body, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        if (body.containsKey("sessionTitle")) {
            sessionService.updateTitle(projectId, sessionId, (String) body.get("sessionTitle"), user);
        }
        if (body.containsKey("modelConfigId") || body.containsKey("useMiniTom")) {
            Long modelConfigId = body.containsKey("modelConfigId") && body.get("modelConfigId") != null
                    ? ((Number) body.get("modelConfigId")).longValue() : null;
            Long promptTemplateId = body.containsKey("promptTemplateId") && body.get("promptTemplateId") != null
                    ? ((Number) body.get("promptTemplateId")).longValue() : null;
            boolean useMiniTom = body.containsKey("useMiniTom") && Boolean.TRUE.equals(body.get("useMiniTom"));
            sessionService.updateConfig(projectId, sessionId, modelConfigId, promptTemplateId, useMiniTom, user);
        }
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> archive(@PathVariable Long projectId, @PathVariable Long sessionId, Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        sessionService.archive(projectId, sessionId, user);
        return ApiResponse.ok(null);
    }

    // ---- Messages — now driven by conversation orchestrator ----

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<GenerationMessageRecord>> listMessages(@PathVariable Long projectId,
                                                                   @PathVariable Long sessionId,
                                                                   Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(messageService.listMessages(sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<ConversationOrchestrator.ConversationReply> sendMessage(@PathVariable Long projectId,
                                                                                @PathVariable Long sessionId,
                                                                                @RequestBody Map<String, String> body,
                                                                                Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        String content = body.getOrDefault("content", "");
        var reply = orchestrator.processUserMessage(sessionId, content, user);
        return ApiResponse.ok(reply);
    }

    // ---- Attachments ----

    @PostMapping("/{sessionId}/attachments")
    public ApiResponse<GenerationAttachmentRecord> uploadAttachment(@PathVariable Long projectId,
                                                                     @PathVariable Long sessionId,
                                                                     @RequestParam("file") MultipartFile file,
                                                                     Authentication auth) throws IOException {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(attachmentService.uploadAndRegister(sessionId, file, user));
    }

    @GetMapping("/{sessionId}/attachments")
    public ApiResponse<List<GenerationAttachmentRecord>> listAttachments(@PathVariable Long projectId,
                                                                         @PathVariable Long sessionId,
                                                                         Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(attachmentService.listBySession(sessionId));
    }

    // ---- Analysis (kept for direct access, but main flow uses orchestrator) ----

    @GetMapping("/{sessionId}/analysis")
    public ApiResponse<RequirementAnalysisRecord> getLatestAnalysis(@PathVariable Long projectId,
                                                                    @PathVariable Long sessionId,
                                                                    Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(analysisService.getLatestAnalysis(sessionId));
    }

    @GetMapping("/{sessionId}/analyses")
    public ApiResponse<List<RequirementAnalysisRecord>> listAnalyses(@PathVariable Long projectId,
                                                                     @PathVariable Long sessionId,
                                                                     Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(analysisService.listAnalyses(sessionId));
    }

    @GetMapping("/{sessionId}/analysis/{version}")
    public ApiResponse<RequirementAnalysisRecord> getAnalysis(@PathVariable Long projectId,
                                                              @PathVariable Long sessionId,
                                                              @PathVariable int version,
                                                              Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(analysisService.getAnalysis(sessionId, version));
    }

    // ---- Drafts ----

    @GetMapping("/{sessionId}/drafts")
    public ApiResponse<List<GenerationDraftView>> listDrafts(@PathVariable Long projectId,
                                                             @PathVariable Long sessionId,
                                                             Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(listDraftsForSession(sessionId));
    }

    public record GenerationDraftView(
            Long id,
            Long sessionId,
            Long analysisId,
            Integer analysisVersion,
            String caseTitle,
            String moduleName,
            String precondition,
            String steps,
            String expectedResult,
            String priority,
            String caseType,
            String status,
            String sourceRefsJson,
            String qualityStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private List<GenerationDraftView> listDraftsForSession(Long sessionId) {
        return jdbc.sql("""
                SELECT id, session_id, analysis_id, analysis_version,
                       case_title, module_name, precondition, steps,
                       expected_result, priority, case_type,
                       case_status AS status, source_refs_json,
                       quality_status, created_at, updated_at
                FROM test_case_draft
                WHERE session_id = :sid ORDER BY id ASC
                """)
                .param("sid", sessionId)
                .query((rs, rowNum) -> new GenerationDraftView(
                        rs.getLong("id"),
                        rs.getObject("session_id") == null ? null : rs.getLong("session_id"),
                        rs.getObject("analysis_id") == null ? null : rs.getLong("analysis_id"),
                        rs.getObject("analysis_version") == null ? null : rs.getInt("analysis_version"),
                        rs.getString("case_title"),
                        rs.getString("module_name"),
                        rs.getString("precondition"),
                        rs.getString("steps"),
                        rs.getString("expected_result"),
                        rs.getString("priority"),
                        rs.getString("case_type"),
                        rs.getString("status"),
                        rs.getString("source_refs_json"),
                        rs.getString("quality_status"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                ))
                .list();
    }

    // ---- Incremental Generation ----

    @PostMapping("/{sessionId}/generate-incremental")
    public ApiResponse<List<GenerationDraftView>> generateIncremental(
            @PathVariable Long projectId,
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);

        Object rawValue = body.get("selectedDraftIds");
        if (rawValue == null) {
            throw new BusinessException("selectedDraftIds 不能为空");
        }
        if (!(rawValue instanceof List)) {
            throw new BusinessException("selectedDraftIds 必须是数组");
        }
        @SuppressWarnings("unchecked")
        List<Object> rawList = (List<Object>) rawValue;
        List<Integer> selectedDraftIds = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (item instanceof Number num) {
                int val = num.intValue();
                if (val > 0) selectedDraftIds.add(val);
            } else {
                throw new BusinessException("selectedDraftIds 第 " + (i + 1) + " 个元素不是有效数字");
            }
        }
        if (selectedDraftIds.isEmpty()) {
            throw new BusinessException("请至少选择一个有效的用例");
        }

        // 校验所选草稿属于当前 session
        int owned = jdbc.sql("SELECT COUNT(*) FROM test_case_draft WHERE session_id = :sid AND id IN (:ids)")
                .param("sid", sessionId).param("ids", selectedDraftIds).query(Integer.class).single();
        if (owned != selectedDraftIds.size()) {
            throw new BusinessException("部分用例不属于当前会话");
        }

        analysisService.incrementalGenerate(sessionId, selectedDraftIds, user);

        return ApiResponse.ok(listDraftsForSession(sessionId));
    }

    private void ensureSessionAccess(Long projectId, Long sessionId, CurrentUser user) {
        sessionService.get(projectId, sessionId, user);
    }
}
