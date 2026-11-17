package com.company.aitest.dsh;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.retrieval.RagRetrievalService;
import com.company.aitest.llm.gateway.retrieval.RetrievalPolicy;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.TomUsageMode;
import com.company.aitest.project.ProjectAccessService;
import com.company.aitest.project.ProjectService;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.generation.FunctionalTestDesignPolicy;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.company.aitest.wiki.WikiService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dsh/tools")
public class DshToolController {
    private final ProjectService projects;
    private final ProjectAccessService access;
    private final ProjectSemanticContextService semantic;
    private final RagRetrievalService retrieval;
    private final DshDraftService drafts;
    private final DshPreferenceService preferences;
    private final DshWorkSessionService workSessions;
    private final JdbcClient jdbc;
    private final MiniTomService miniTom;
    private final WikiService wiki;

    public DshToolController(ProjectService projects, ProjectAccessService access,
                             ProjectSemanticContextService semantic, RagRetrievalService retrieval,
                             DshDraftService drafts, DshPreferenceService preferences,
                             DshWorkSessionService workSessions, JdbcClient jdbc, MiniTomService miniTom,
                             WikiService wiki) {
        this.projects = projects;
        this.access = access;
        this.semantic = semantic;
        this.retrieval = retrieval;
        this.drafts = drafts;
        this.preferences = preferences;
        this.workSessions = workSessions;
        this.jdbc = jdbc;
        this.miniTom = miniTom;
        this.wiki = wiki;
    }

    @GetMapping("/projects")
    public ApiResponse<?> projects(Authentication authentication) {
        return ApiResponse.ok(projects.list(user(authentication)));
    }

    @GetMapping("/projects/{projectId}/tom")
    public ApiResponse<?> tom(@PathVariable Long projectId, @RequestParam String mode,
                              Authentication authentication) {
        CurrentUser user = user(authentication);
        access.ensureCanAccess(projectId, user);
        if ("DIRECT".equals(mode)) return ApiResponse.ok(List.of());
        if ("PROJECT_TOM".equals(mode)) return ApiResponse.ok(miniTom.listActive(projectId, null, user)
                .stream().filter(item -> "PROJECT".equals(item.scope())).toList());
        if ("PROJECT_AND_SYSTEM_TOM".equals(mode)) return ApiResponse.ok(miniTom.listActive(projectId, null, user));
        throw new BusinessException("TOM 模式无效");
    }

    @GetMapping("/design-rules")
    public ApiResponse<?> designRules() {
        return ApiResponse.ok(List.of("MAIN_FLOW", "BRANCH", "BOUNDARY", "EXCEPTION", "STATE", "DATA", "AUTH", "CONCURRENCY", "IDEMPOTENT")
                .stream().map(type -> Map.of("pointType", type,
                        "designMethods", FunctionalTestDesignPolicy.designMethods(type, null),
                        "coverageRequirements", FunctionalTestDesignPolicy.coverageRequirements(type))).toList());
    }

    @GetMapping("/case-writing-standard")
    public ApiResponse<?> caseWritingStandard() {
        return ApiResponse.ok(Map.of(
                "columns", List.of("用例名称", "所属模块", "标签", "前置条件", "备注", "步骤描述", "预期结果"),
                "moduleFormat", "/页面/功能/子功能",
                "stepRule", "原子检查可以只有一步；完整流程从已知入口按真实操作顺序分步，每步写清所在页面、操作对象、动作和必要数据，直到触发被验证结果。步骤和预期从1连续编号且逐项对应，不为凑数增加点击。",
                "independenceRule", "每条用例都必须能单独执行，不能默认前一条用例已运行、已通过或留下数据。若需要特定账号、开关、页面状态或业务数据，在本条前置条件中写清可独立准备的状态与数据；需要操作才能到达的状态，在本条步骤中从已知入口写出完整操作。用例顺序只用于组织展示，不构成执行依赖。",
                "caseOutlineRule", "先为每组测试点确定角色、可准备的数据、已确认入口、顺序动作、观察位置以及需要分开的异常或边界，再写用例。不要把测试点类型或覆盖标签直接改写成用例标题。",
                "qualityReviewRule", "后台校验通过后仍须逐条核对真实业务语义：把用例单独交给未读对话的测试人员，前置是否能准备、菜单与字段是否有来源、操作是否可复现、每步预期是否在该步后可观察。相关历史用例按模块检索完整步骤，只参考人类表达方式，不复制未经确认的业务事实。",
                "statefulRule", "涉及既存对象、已有数据或状态变更的操作时，本条前置条件必须说明可独立准备的对象、数据和初始状态，或者本条步骤必须包含创建与获取过程；不能默认前一条用例留下了数据。",
                "contentRule", "标题写清业务对象、操作和结果，不用‘2个月可提交’等孤立结论；预期逐步写可观察的页面、提示、字段值或记录状态，禁止‘选择成功’‘保存成功’等空泛描述。前置条件只写执行前必须具备的账号、数据和开关，没有就留空；标签、备注可留空。",
                "sourceRule", "菜单、按钮、字段、提示的文字必须来自本轮需求、澄清、已审核资产或实测界面；未知时先核实或明确标记待确认，不凭业务常识补造菜单路径。样例只用于学习表达方式，不复制业务事实。"
        ));
    }

    @GetMapping("/projects/{projectId}/context")
    public ApiResponse<?> context(@PathVariable Long projectId,
                                  @RequestParam(defaultValue = "") String focus,
                                  @RequestParam(defaultValue = "PROJECT_AND_SYSTEM_TOM") String mode,
                                  Authentication authentication) {
        CurrentUser user = user(authentication);
        access.ensureCanAccess(projectId, user);
        TomUsageMode resolvedMode;
        try { resolvedMode = TomUsageMode.valueOf(mode); }
        catch (IllegalArgumentException ex) { throw new BusinessException("TOM 模式无效"); }
        var signals = semantic.build(projectId, focus, List.of(), 10, resolvedMode).signals();
        return ApiResponse.ok(Map.of("project", projects.get(projectId), "signals", signals));
    }

    @GetMapping("/projects/{projectId}/wiki/context")
    public ApiResponse<?> wikiContext(@PathVariable Long projectId,
                                      @RequestParam(defaultValue = "") String query,
                                      @RequestParam(defaultValue = "8") int limit,
                                      Authentication authentication) {
        access.ensureCanAccess(projectId, user(authentication));
        return ApiResponse.ok(wiki.activeContext(projectId, query, limit));
    }

    @GetMapping("/projects/{projectId}/wiki/entries/{entryId}")
    public ApiResponse<?> wikiEntry(@PathVariable Long projectId, @PathVariable Long entryId,
                                    Authentication authentication) {
        access.ensureCanAccess(projectId, user(authentication));
        return ApiResponse.ok(wiki.activeEntry(projectId, entryId));
    }

    @PostMapping("/projects/{projectId}/assets/search")
    public ApiResponse<?> search(@PathVariable Long projectId, @Valid @RequestBody SearchRequest request,
                                 Authentication authentication) {
        CurrentUser user = user(authentication);
        access.ensureCanAccess(projectId, user);
        int limit = Math.max(1, Math.min(request.limit() == null ? 10 : request.limit(), 30));
        String query = request.query() == null ? "" : request.query().strip();
        if (query.length() > 100) throw new BusinessException("资产检索词最多 100 字");
        var policy = new RetrievalPolicy(false, null, limit, false, false, query);
        var invocation = new LlmInvocationRequest(null, user.id(), projectId, null,
                "DSH_ASSET_SEARCH", null, null, null, null, Map.of(), null, null, null);
        return ApiResponse.ok(retrieval.searchForDsh(invocation, policy).assets());
    }

    @GetMapping("/projects/{projectId}/cases")
    public ApiResponse<?> cases(@PathVariable Long projectId, @RequestParam(defaultValue = "20") int limit,
                                @RequestParam(defaultValue = "") String query,
                                @RequestParam(required = false) Long beforeId,
                                Authentication authentication) {
        CurrentUser user = user(authentication);
        access.ensureCanAccess(projectId, user);
        if (query.length() > 200) throw new BusinessException("用例检索词最多 200 字");
        if (beforeId != null && beforeId < 1) throw new BusinessException("翻页游标无效");
        StringBuilder sql = new StringBuilder("""
                SELECT id, case_no, case_title, module_name, precondition, steps, expected_result,
                    priority, case_type, scenario_type, design_method, source_refs_json,
                    import_source_type, import_source_ref_id, version, updated_at
                FROM test_case_asset
                WHERE project_id = :projectId AND case_status = 'SUBMITTED' AND deprecated_at IS NULL
                  AND (case_scope IS NULL OR case_scope IN ('PROJECT', 'PUBLIC', 'SYSTEM')
                       OR (case_scope = 'PERSONAL' AND submitted_by = :userId))
                """);
        if (!query.isBlank()) {
            sql.append(" AND (case_no LIKE :pattern ESCAPE '!' OR case_title LIKE :pattern ESCAPE '!' ")
                    .append("OR module_name LIKE :pattern ESCAPE '!' OR steps LIKE :pattern ESCAPE '!' ")
                    .append("OR expected_result LIKE :pattern ESCAPE '!')");
        }
        if (beforeId != null) sql.append(" AND id < :beforeId");
        sql.append(" ORDER BY id DESC LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("projectId", projectId).param("userId", user.id())
                .param("limit", Math.max(1, Math.min(limit, 50)));
        if (!query.isBlank()) {
            String pattern = "%" + query.strip().replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%";
            statement = statement.param("pattern", pattern);
        }
        if (beforeId != null) statement = statement.param("beforeId", beforeId);
        return ApiResponse.ok(statement
                .query((rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("caseNo", rs.getString("case_no"));
                    item.put("caseTitle", rs.getString("case_title"));
                    item.put("moduleName", rs.getString("module_name"));
                    item.put("precondition", rs.getString("precondition"));
                    item.put("steps", rs.getString("steps"));
                    item.put("expectedResult", rs.getString("expected_result"));
                    item.put("priority", rs.getString("priority"));
                    item.put("caseType", rs.getString("case_type"));
                    item.put("scenarioType", rs.getString("scenario_type"));
                    item.put("designMethod", rs.getString("design_method"));
                    item.put("sourceRefsJson", rs.getString("source_refs_json"));
                    item.put("importSourceType", rs.getString("import_source_type"));
                    item.put("importSourceRefId", rs.getObject("import_source_ref_id"));
                    item.put("version", rs.getInt("version"));
                    return item;
                })
                .list());
    }

    @GetMapping("/projects/{projectId}/cases/{caseId}")
    public ApiResponse<?> caseDetail(@PathVariable Long projectId, @PathVariable Long caseId,
                                     Authentication authentication) {
        CurrentUser user = user(authentication);
        access.ensureCanAccess(projectId, user);
        if (caseId == null || caseId < 1) throw new BusinessException("用例编号无效");
        var cases = jdbc.sql("""
                SELECT id, case_no, case_title, module_name, precondition, steps, expected_result,
                       priority, case_type, scenario_type, design_method, source_refs_json,
                       import_source_type, import_source_ref_id, version, updated_at
                FROM test_case_asset
                WHERE id = :caseId AND project_id = :projectId AND case_status = 'SUBMITTED'
                  AND deprecated_at IS NULL
                  AND (case_scope IS NULL OR case_scope IN ('PROJECT', 'PUBLIC', 'SYSTEM')
                       OR (case_scope = 'PERSONAL' AND submitted_by = :userId))
                """).param("caseId", caseId).param("projectId", projectId).param("userId", user.id())
                .query((rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("caseNo", rs.getString("case_no"));
                    item.put("caseTitle", rs.getString("case_title"));
                    item.put("moduleName", rs.getString("module_name"));
                    item.put("precondition", rs.getString("precondition"));
                    item.put("steps", rs.getString("steps"));
                    item.put("expectedResult", rs.getString("expected_result"));
                    item.put("priority", rs.getString("priority"));
                    item.put("caseType", rs.getString("case_type"));
                    item.put("scenarioType", rs.getString("scenario_type"));
                    item.put("designMethod", rs.getString("design_method"));
                    item.put("sourceRefsJson", rs.getString("source_refs_json"));
                    item.put("importSourceType", rs.getString("import_source_type"));
                    item.put("importSourceRefId", rs.getObject("import_source_ref_id"));
                    item.put("version", rs.getInt("version"));
                    return item;
                }).list();
        if (cases.isEmpty()) throw new BusinessException("正式用例不存在或当前不可见");
        return ApiResponse.ok(cases.get(0));
    }

    @GetMapping("/projects/{projectId}/historical-style-examples")
    public ApiResponse<?> historicalStyleExamples(@PathVariable Long projectId,
                                                   @RequestParam(defaultValue = "8") int limit,
                                                   @RequestParam(defaultValue = "") String query,
                                                   Authentication authentication) {
        CurrentUser user = user(authentication);
        access.ensureCanAccess(projectId, user);
        int remaining = Math.max(1, Math.min(limit, 12));
        String focus = query == null ? "" : query.strip();
        if (focus.length() > 80) throw new BusinessException("历史用例检索词最多 80 字");
        String pattern = "%" + focus.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        List<Long> sourceProjects = new java.util.ArrayList<>();
        sourceProjects.add(projectId);
        sourceProjects.addAll(miniTom.listActive(projectId, null, user).stream()
                .filter(tom -> "SYSTEM".equals(tom.scope()))
                .map(tom -> tom.sourceProjectId() != null ? tom.sourceProjectId() : tom.projectId())
                .filter(sourceId -> sourceId != null && !projectId.equals(sourceId))
                .distinct().toList());
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Long sourceProjectId : sourceProjects) {
            if (remaining == 0) break;
            try { access.ensureCanAccess(sourceProjectId, user); }
            catch (BusinessException denied) { continue; }
            List<Map<String, Object>> examples = jdbc.sql("""
                    SELECT i.id, i.case_title, i.module_name, i.precondition, i.steps, i.expected_result,
                           b.batch_name
                    FROM historical_case_import_item i
                    JOIN historical_case_import b ON b.id = i.import_id
                    WHERE i.project_id = :sourceProjectId AND i.eligibility_status = 'ELIGIBLE'
                      AND CHAR_LENGTH(TRIM(COALESCE(i.steps, ''))) >= 12
                      AND CHAR_LENGTH(TRIM(COALESCE(i.expected_result, ''))) >= 12
                      AND (:focus = '' OR i.case_title LIKE :pattern ESCAPE '!'
                           OR i.module_name LIKE :pattern ESCAPE '!'
                           OR i.steps LIKE :pattern ESCAPE '!')
                    ORDER BY i.id DESC LIMIT :limit
                    """).param("sourceProjectId", sourceProjectId).param("limit", remaining)
                    .param("focus", focus).param("pattern", pattern)
                    .query((rs, rowNum) -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", rs.getLong("id"));
                        item.put("sourceProjectId", sourceProjectId);
                        item.put("importBatch", rs.getString("batch_name"));
                        item.put("caseTitle", rs.getString("case_title"));
                        item.put("moduleName", rs.getString("module_name"));
                        item.put("precondition", rs.getString("precondition"));
                        item.put("steps", rs.getString("steps"));
                        item.put("expectedResult", rs.getString("expected_result"));
                        item.put("styleOnly", true);
                        return item;
                    }).list();
            result.addAll(examples);
            remaining -= examples.size();
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/projects/{projectId}/drafts")
    public ApiResponse<DshDraftService.SavedDraft> saveDraft(@PathVariable Long projectId,
                                                              @Valid @RequestBody DshDraftService.DraftInput input,
                                                              Authentication authentication) {
        return ApiResponse.ok(drafts.save(projectId, input, user(authentication)));
    }

    @GetMapping("/projects/{projectId}/drafts/{draftId}/revision")
    public ApiResponse<Map<String, Object>> draftRevision(@PathVariable Long projectId, @PathVariable Long draftId,
            Authentication authentication) {
        return ApiResponse.ok(drafts.currentRevision(projectId, draftId, user(authentication)));
    }

    @PostMapping("/projects/{projectId}/drafts/{draftId}/revision")
    public ApiResponse<DshDraftService.RevisedDraft> reviseDraft(@PathVariable Long projectId, @PathVariable Long draftId,
            @RequestBody DshDraftService.DraftRevisionInput input, Authentication authentication) {
        return ApiResponse.ok(drafts.revise(projectId, draftId, input, user(authentication)));
    }

    @GetMapping("/projects/{projectId}/preference")
    public ApiResponse<DshPreferenceService.Preference> preference(@PathVariable Long projectId,
                                                                    Authentication authentication) {
        return ApiResponse.ok(preferences.get(projectId, user(authentication)));
    }

    @PostMapping("/projects/{projectId}/preference-candidates")
    public ApiResponse<DshPreferenceService.Candidate> proposePreference(@PathVariable Long projectId,
                                                                          @Valid @RequestBody PreferenceCandidateRequest request,
                                                                          Authentication authentication) {
        return ApiResponse.ok(preferences.propose(projectId, request.styleText(), request.reason(),
                request.sourceDraftId(), user(authentication)));
    }

    @PostMapping("/projects/{projectId}/work-sessions")
    public ApiResponse<DshWorkSessionService.WorkSession> startWork(@PathVariable Long projectId,
                                                                      @Valid @RequestBody StartWorkRequest request,
                                                                      Authentication authentication) {
        return ApiResponse.ok(workSessions.start(projectId, request.requirementText(), request.tomMode(), user(authentication)));
    }

    @GetMapping("/projects/{projectId}/work-sessions/{sessionId}")
    public ApiResponse<DshWorkSessionService.WorkSession> work(@PathVariable Long projectId,
                                                                 @PathVariable Long sessionId,
                                                                 Authentication authentication) {
        return ApiResponse.ok(workSessions.get(projectId, sessionId, user(authentication)));
    }

    @GetMapping("/projects/{projectId}/work-sessions/{sessionId}/stages")
    public ApiResponse<List<DshWorkSessionService.StageSnapshot>> stages(@PathVariable Long projectId,
                                                                            @PathVariable Long sessionId,
                                                                            Authentication authentication) {
        return ApiResponse.ok(workSessions.stages(projectId, sessionId, user(authentication)));
    }

    @PostMapping("/projects/{projectId}/work-sessions/{sessionId}/stages")
    public ApiResponse<DshWorkSessionService.StageSnapshot> saveStage(@PathVariable Long projectId,
                                                                        @PathVariable Long sessionId,
                                                                        @Valid @RequestBody StageRequest request,
                                                                        Authentication authentication) {
        return ApiResponse.ok(workSessions.saveStage(projectId, sessionId, request.stageKey(),
                request.payloadJson(), user(authentication)));
    }

    @PostMapping("/projects/{projectId}/work-sessions/{sessionId}/case-design/validate")
    public ApiResponse<DshWorkSessionService.BatchValidation> validateCaseBatch(@PathVariable Long projectId,
            @PathVariable Long sessionId, @Valid @RequestBody CaseBatchRequest request,
            Authentication authentication) {
        return ApiResponse.ok(workSessions.validateCaseBatch(projectId, sessionId, request.pointIds(),
                request.payloadJson(), user(authentication)));
    }

    private CurrentUser user(Authentication authentication) {
        return ((DshConnectionService.DshPrincipal) authentication.getPrincipal()).user();
    }

    public record SearchRequest(String query, Integer limit) {}
    public record StartWorkRequest(@NotBlank String requirementText, @NotBlank String tomMode) {}
    public record StageRequest(@NotBlank String stageKey, @NotBlank String payloadJson) {}
    public record CaseBatchRequest(List<String> pointIds, @NotBlank String payloadJson) {}
    public record PreferenceCandidateRequest(@NotBlank String styleText, @NotBlank String reason,
                                             Long sourceDraftId) {}
}
