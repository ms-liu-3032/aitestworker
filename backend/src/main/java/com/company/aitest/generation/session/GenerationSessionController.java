package com.company.aitest.generation.session;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.AsyncGenerationTaskService;
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
    private final AsyncGenerationTaskService asyncGenerationTaskService;
    private final JdbcClient jdbc;

    public GenerationSessionController(GenerationSessionService sessionService,
                                        GenerationMessageService messageService,
                                        GenerationAttachmentService attachmentService,
                                        RequirementAnalysisService analysisService,
                                        ConversationOrchestrator orchestrator,
                                        AsyncGenerationTaskService asyncGenerationTaskService,
                                        JdbcClient jdbc) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.analysisService = analysisService;
        this.orchestrator = orchestrator;
        this.asyncGenerationTaskService = asyncGenerationTaskService;
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
        if (body.containsKey("modelConfigId") || body.containsKey("promptTemplateId")
                || body.containsKey("useMiniTom") || body.containsKey("tomMode")) {
            GenerationSessionRecord current = sessionService.get(projectId, sessionId, user);
            Long modelConfigId = body.containsKey("modelConfigId")
                    ? body.get("modelConfigId") == null ? null : ((Number) body.get("modelConfigId")).longValue()
                    : current.modelConfigId();
            Long promptTemplateId = body.containsKey("promptTemplateId")
                    ? body.get("promptTemplateId") == null ? null : ((Number) body.get("promptTemplateId")).longValue()
                    : current.promptTemplateId();
            boolean useMiniTom = body.containsKey("useMiniTom") && Boolean.TRUE.equals(body.get("useMiniTom"));
            String tomMode = body.get("tomMode") == null ? null : String.valueOf(body.get("tomMode"));
            if (tomMode == null && body.containsKey("useMiniTom")) {
                sessionService.updateConfig(projectId, sessionId, modelConfigId, promptTemplateId, useMiniTom, user);
            } else {
                if (tomMode == null) {
                    tomMode = current.tomMode();
                }
                sessionService.updateConfig(projectId, sessionId, modelConfigId, promptTemplateId, tomMode, user);
            }
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

    @PostMapping("/{sessionId}/analysis-async")
    public ApiResponse<AsyncGenerationTaskService.TaskView> analysisAsync(@PathVariable Long projectId,
                                                                          @PathVariable Long sessionId,
                                                                          @RequestBody(required = false) Map<String, String> body,
                                                                          Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        String content = body == null ? "" : body.getOrDefault("content", "");
        var task = asyncGenerationTaskService.startSessionRequirementAnalysis(projectId, sessionId, content, user);
        return ApiResponse.ok(task);
    }

    @PostMapping("/{sessionId}/generate-async")
    public ApiResponse<AsyncGenerationTaskService.TaskView> generateAsync(@PathVariable Long projectId,
                                                                          @PathVariable Long sessionId,
                                                                          @RequestBody(required = false) Map<String, String> body,
                                                                          Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        var task = asyncGenerationTaskService.startSessionCaseGeneration(projectId, sessionId, user);
        String content = body == null ? "" : body.getOrDefault("content", "");
        if (content != null && !content.isBlank()) {
            messageService.appendUserMessage(sessionId, content, user);
        }
        var latest = analysisService.getLatestAnalysis(sessionId);
        int analysisVersion = latest == null ? 0 : latest.version();
        messageService.appendAssistantMessage(sessionId,
                "已创建用例生成任务 #" + task.taskId() + "，正在后台生成草稿。生成期间重复点击不会创建重复模型调用。",
                null,
                "SYSTEM_CASE_GENERATING",
                analysisVersion);
        return ApiResponse.ok(task);
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
        return ApiResponse.ok(toClientAnalysis(analysisService.getLatestAnalysis(sessionId)));
    }

    @GetMapping("/{sessionId}/analyses")
    public ApiResponse<List<RequirementAnalysisRecord>> listAnalyses(@PathVariable Long projectId,
                                                                     @PathVariable Long sessionId,
                                                                     Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(analysisService.listAnalyses(sessionId).stream()
                .map(this::toClientAnalysis)
                .toList());
    }

    @GetMapping("/{sessionId}/analysis/{version}")
    public ApiResponse<RequirementAnalysisRecord> getAnalysis(@PathVariable Long projectId,
                                                              @PathVariable Long sessionId,
                                                              @PathVariable int version,
                                                              Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        return ApiResponse.ok(toClientAnalysis(analysisService.getAnalysis(sessionId, version)));
    }

    public record TestPointScopeRequest(List<RequirementAnalysisService.TestPointScopeDecision> decisions) {
    }

    public record RequirementScopeRequest(List<RequirementAnalysisService.RequirementScopeDecision> decisions) {
    }

    @PutMapping("/{sessionId}/analysis/{version}/requirement-scope")
    public ApiResponse<AsyncGenerationTaskService.TaskView> confirmRequirementScope(@PathVariable Long projectId,
                                                                                     @PathVariable Long sessionId,
                                                                                     @PathVariable int version,
                                                                                     @RequestBody RequirementScopeRequest body,
                                                                                     Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        if (body == null || body.decisions() == null) {
            throw new BusinessException("需求范围决定不能为空");
        }
        return ApiResponse.ok(asyncGenerationTaskService.confirmRequirementScopeAndContinue(
                projectId, sessionId, version, body.decisions(), user));
    }

    @PutMapping("/{sessionId}/analysis/{version}/test-point-scope")
    public ApiResponse<AsyncGenerationTaskService.TaskView> confirmTestPointScope(@PathVariable Long projectId,
                                                                                  @PathVariable Long sessionId,
                                                                                  @PathVariable int version,
                                                                                  @RequestBody TestPointScopeRequest body,
                                                                                  Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        if (body == null || body.decisions() == null) {
            throw new BusinessException("测试点范围决定不能为空");
        }
        return ApiResponse.ok(asyncGenerationTaskService.confirmTestPointScopeAndContinue(
                projectId, sessionId, version, body.decisions(), user));
    }


    private RequirementAnalysisRecord toClientAnalysis(RequirementAnalysisRecord analysis) {
        if (analysis == null) {
            return null;
        }
        return new RequirementAnalysisRecord(
                analysis.id(),
                analysis.sessionId(),
                analysis.version(),
                analysis.subVersion(),
                analysis.requirementText(),
                analysis.analysisResult(),
                null,
                analysis.clarificationQuestions(),
                analysis.clarificationAnswers(),
                analysis.assumptions(),
                analysis.testPoints(),
                analysis.affectedCases(),
                analysis.changeScope(),
                analysis.newCasesNeeded(),
                analysis.status(),
                analysis.createdAt(),
                analysis.updatedAt());
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

    @PostMapping("/{sessionId}/generate-incremental-async")
    public ApiResponse<AsyncGenerationTaskService.TaskView> generateIncrementalAsync(
            @PathVariable Long projectId,
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);
        List<Integer> selectedDraftIds = parseSelectedDraftIds(body);
        ensureDraftOwnership(sessionId, selectedDraftIds);
        var task = asyncGenerationTaskService.startSessionIncrementalGeneration(projectId, sessionId, selectedDraftIds, user);
        return ApiResponse.ok(task);
    }

    @PostMapping("/{sessionId}/generate-incremental")
    public ApiResponse<List<GenerationDraftView>> generateIncremental(
            @PathVariable Long projectId,
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        ensureSessionAccess(projectId, sessionId, user);

        List<Integer> selectedDraftIds = parseSelectedDraftIds(body);
        ensureDraftOwnership(sessionId, selectedDraftIds);

        analysisService.incrementalGenerate(sessionId, selectedDraftIds, user);

        return ApiResponse.ok(listDraftsForSession(sessionId));
    }

    private List<Integer> parseSelectedDraftIds(Map<String, Object> body) {
        if (body == null) {
            throw new BusinessException("selectedDraftIds 不能为空");
        }
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
        return selectedDraftIds;
    }

    private void ensureDraftOwnership(Long sessionId, List<Integer> selectedDraftIds) {
        int owned = jdbc.sql("SELECT COUNT(*) FROM test_case_draft WHERE session_id = :sid AND id IN (:ids)")
                .param("sid", sessionId).param("ids", selectedDraftIds).query(Integer.class).single();
        if (owned != selectedDraftIds.size()) {
            throw new BusinessException("部分用例不属于当前会话");
        }
    }

    private void ensureSessionAccess(Long projectId, Long sessionId, CurrentUser user) {
        sessionService.get(projectId, sessionId, user);
    }
}
