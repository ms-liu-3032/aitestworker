package com.company.aitest.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.generation.session.GenerationCaseLibraryService;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import com.company.aitest.scan.ControlledScanService;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceAssetService {
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是资深测试分析师。请基于真实测试执行轨迹，输出结构化摘要。
            要求：
            1. 不要虚构未发生的步骤。
            2. 明确区分操作路径、关键页面、角色切换、网络异常和可复用测试价值。
            3. 用中文输出，适合沉淀为测试资产摘要。
            4. 输出分段文本，包含：轨迹概览、关键步骤、异常观察、建议标签、建议用例类型。
            """;

    private static final String CASE_SYSTEM_PROMPT = """
            你是资深测试工程师。请严格基于真实操作轨迹生成测试用例草稿。
            要求：
            1. 只能使用已发生的操作和已观察到的结果，不扩展未发生的业务分支。
            2. 返回 JSON 数组，不要输出 markdown，不要输出额外解释。
            3. 每个对象字段固定为：
               caseTitle, moduleName, precondition, steps, expectedResult, priority
            4. priority 只允许 P0/P1/P2/P3/P4。
            5. 多角色流程步骤中，请在步骤前加【角色名】。
            6. 输出的是标准功能测试用例，不是 UI 自动化脚本。
            7. 禁止出现 locator、xpath、selector、click()、fill()、press()、page.、Playwright、Selenium、Cypress。
            8. steps 必须写成业务动作，例如“打开页面”“输入字段”“点击提交”“跳转到结果页”。
            9. expectedResult 必须写成页面结果或业务结果，不要写代码断言。
            """;

    private static final String SKILL_SYSTEM_PROMPT = """
            你是资深测试架构师。请基于真实测试轨迹提炼一个受控测试 Skill 模板。
            要求：
            1. Skill 不是自动化脚本，而是测试流程经验模板。
            2. 严格基于真实轨迹，不要补未发生的业务分支。
            3. 返回 JSON 对象，不要输出 markdown。
            4. 字段固定为：skillName, applicableScene, flowSteps。
            5. flowSteps 使用中文编号步骤，适合测试人员复用。
            """;

    private static final String TOOL_SYSTEM_PROMPT = """
            你是资深测试工程师。请基于真实测试轨迹提炼可复用操作 Tool 模板。
            要求：
            1. Tool 是可复用操作动作描述，不是自动化脚本。
            2. 严格基于真实轨迹，不要补未发生的步骤。
            3. 返回 JSON 数组，不要输出 markdown。
            4. 每个对象字段固定为：toolName, operationSteps。
            5. operationSteps 使用中文编号步骤，适合测试人员理解和复用。
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final TraceDataService traceDataService;
    private final BrowserTraceGroupService groupService;
    private final BrowserTraceSessionService sessionService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final TraceStepNormalizer traceStepNormalizer;
    private final ControlledScanService controlledScanService;
    private final TraceCorrectionSuggestionService correctionService;
    private final ProjectSemanticContextService semanticContextService;
    private final List<TraceRulePack> traceRulePacks;

    public TraceAssetService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                             TraceDataService traceDataService, BrowserTraceGroupService groupService,
                             BrowserTraceSessionService sessionService,
                             LlmGateway llmGateway,
                             ControlledScanService controlledScanService,
                             TraceCorrectionSuggestionService correctionService,
                             ProjectSemanticContextService semanticContextService,
                             List<TraceRulePack> traceRulePacks) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.traceDataService = traceDataService;
        this.groupService = groupService;
        this.sessionService = sessionService;
        this.llmGateway = llmGateway;
        this.controlledScanService = controlledScanService;
        this.correctionService = correctionService;
        this.semanticContextService = semanticContextService;
        this.traceRulePacks = traceRulePacks;
        this.objectMapper = new ObjectMapper();
        this.traceStepNormalizer = new TraceStepNormalizer(this.traceRulePacks);
    }

    @Transactional
    public KnowledgeAssetRecord generateSummary(Long groupId, GenerateSummaryCommand command, CurrentUser user) {
        TraceDataService.TraceGroupDetail detail = traceDataService.detail(groupId, user);
        TraceSlice slice = buildSlice(detail, command.traceSessionId(), command.issueClipId(), user);
        String prompt = buildSummaryPrompt(detail, slice);
        String output = invokeLlm(command.modelConfigId(),
                detail.group().projectId(), detail.group().id(),
                LlmStage.TRACE_SUMMARY, "TRACE",
                SUMMARY_SYSTEM_PROMPT, prompt, user);

        LocalDateTime now = timeProvider.now();
        String title = switch (slice.scopeType()) {
            case "ISSUE_CLIP" -> "问题片段摘要 #" + command.issueClipId();
            case "TRACE_SESSION" -> "轨迹会话摘要 #" + command.traceSessionId();
            default -> "轨迹组摘要 #" + groupId;
        };
        jdbcTemplate.update("""
                insert into knowledge_asset(project_id, asset_type, asset_ref_type, asset_ref_id, title, content,
                  status, visibility, created_by, created_at, updated_at)
                values (?, 'TRACE_SUMMARY', ?, ?, ?, ?, 'DRAFT', 'PRIVATE', ?, ?, ?)
                """, detail.group().projectId(), slice.scopeType(), slice.scopeId(), title,
                output == null || output.isBlank() ? "暂无可用摘要，请稍后重试。" : output, user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getKnowledgeAsset(id);
    }

    public List<KnowledgeAssetRecord> listSummaries(Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = groupService.getById(groupId, user);
        return jdbc.sql("""
                select * from knowledge_asset
                where project_id = :projectId
                  and asset_type = 'TRACE_SUMMARY'
                  and (
                    (asset_ref_type = 'TRACE_GROUP' and asset_ref_id = :groupId)
                    or asset_ref_id in (
                        select id from browser_trace_session where trace_group_id = :groupId
                    )
                    or asset_ref_id in (
                        select id from browser_issue_clip where trace_group_id = :groupId
                    )
                  )
                order by id desc
                """).param("projectId", group.projectId()).param("groupId", groupId).query(this::mapKnowledgeAsset).list();
    }

    public List<CleanStepView> listCleanSteps(Long groupId, Long traceSessionId, Long issueClipId, CurrentUser user) {
        TraceDataService.TraceGroupDetail detail = traceDataService.detail(groupId, user);
        TraceSlice slice = buildSlice(detail, traceSessionId, issueClipId, user);
        List<TraceStepNormalizer.CleanTraceStep> steps = buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks());
        steps = correctionService.applyLearnedPatternsToCleanSteps(detail.group().projectId(), steps, slice.events());
        // 应用已确认修正建议到清洗后步骤（即时作用于当前轨迹上下文）
        List<TraceCorrectionCandidateRecord> confirmed = correctionService.getConfirmedForSummary(groupId, traceSessionId, issueClipId);
        steps = correctionService.applyConfirmedToCleanSteps(steps, confirmed);
        steps = correctionService.applyStepCorrectionsToCleanSteps(steps, confirmed);
        return steps.stream()
                .map(step -> new CleanStepView(step.stepNo(), step.actor(), step.actionType(), step.description(),
                        step.pageName(), step.pageUrl(), step.relativeMs()))
                .toList();
    }

    @Transactional
    public List<TraceGeneratedCaseRecord> generateCases(Long groupId, GenerateCasesCommand command, CurrentUser user) {
        TraceDataService.TraceGroupDetail detail = traceDataService.detail(groupId, user);
        TraceSlice slice = buildSlice(detail, command.traceSessionId(), command.issueClipId(), user);
        List<TraceCorrectionCandidateRecord> confirmed = correctionService.getConfirmedForSummary(
                groupId, command.traceSessionId(), command.issueClipId());
        String userPrompt = buildCasePrompt(command.caseType(), detail, slice, confirmed);
        String output = invokeLlm(command.modelConfigId(),
                detail.group().projectId(), detail.group().id(),
                LlmStage.TRACE_CASE_GEN, "TRACE_CASE",
                CASE_SYSTEM_PROMPT, userPrompt, user);
        List<CaseDraftInput> fallbackCases = buildFallbackCases(command.caseType(), detail, slice, confirmed);
        List<CaseDraftInput> cases = parseCaseOutput(output, groupId, fallbackCases);
        LocalDateTime now = timeProvider.now();
        List<TraceGeneratedCaseRecord> result = new ArrayList<>();
        for (CaseDraftInput item : cases) {
            jdbcTemplate.update("""
                    insert into trace_generated_case(project_id, user_id, trace_group_id, trace_session_id, issue_clip_id,
                      case_type, case_title, module_name, precondition, steps, expected_result, priority,
                      case_scope, case_status, source_refs_json, model_config_id, prompt_snapshot, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PERSONAL', 'DRAFT', ?, ?, ?, ?, ?)
                    """, detail.group().projectId(), user.id(), detail.group().id(), command.traceSessionId(),
                    command.issueClipId(), command.caseType(), item.caseTitle(), item.moduleName(), item.precondition(),
                    item.steps(), item.expectedResult(), normalizePriority(item.priority()),
                    buildTraceSourceRefsJson(detail.group().id(), command.traceSessionId(), command.issueClipId()),
                    command.modelConfigId(), buildPromptSnapshot(CASE_SYSTEM_PROMPT, userPrompt), now, now);
            Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
            result.add(getTraceGeneratedCase(id));
        }
        return result;
    }

    public List<TraceGeneratedCaseRecord> listGeneratedCases(Long projectId, CurrentUser user) {
        return jdbc.sql("""
                select * from trace_generated_case
                where project_id = :projectId and user_id = :userId
                order by id desc
                """).param("projectId", projectId).param("userId", user.id()).query(this::mapTraceGeneratedCase).list();
    }

    /** Trace group assets are paged and projected without long case bodies for list rendering. */
    public GeneratedCasePage listGeneratedCasePage(Long projectId, Long traceGroupId, int page, int size,
                                                    CurrentUser user) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        int offset = safePage * safeSize;
        Integer total = jdbc.sql("""
                SELECT COUNT(*) FROM trace_generated_case
                WHERE project_id = :projectId AND user_id = :userId AND trace_group_id = :traceGroupId
                """).param("projectId", projectId).param("userId", user.id()).param("traceGroupId", traceGroupId)
                .query(Integer.class).single();
        List<TraceGeneratedCaseRecord> items = jdbc.sql("""
                SELECT id, project_id, user_id, trace_group_id, trace_session_id, issue_clip_id,
                       case_type, case_title, module_name, NULL AS precondition, NULL AS steps,
                       NULL AS expected_result, priority, case_scope, case_status,
                       NULL AS source_refs_json, model_config_id, NULL AS prompt_snapshot,
                       created_at, updated_at
                FROM trace_generated_case
                WHERE project_id = :projectId AND user_id = :userId AND trace_group_id = :traceGroupId
                ORDER BY updated_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """).param("projectId", projectId).param("userId", user.id()).param("traceGroupId", traceGroupId)
                .param("limit", safeSize).param("offset", offset)
                .query(this::mapTraceGeneratedCase).list();
        return new GeneratedCasePage(items, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional
    public TestSkillTemplateRecord generateSkillTemplate(Long groupId, GenerateAssetCommand command, CurrentUser user) {
        TraceDataService.TraceGroupDetail detail = traceDataService.detail(groupId, user);
        TraceSlice slice = buildSlice(detail, command.traceSessionId(), command.issueClipId(), user);
        String prompt = buildSkillPrompt(detail, slice);
        String output = invokeLlm(command.modelConfigId(),
                detail.group().projectId(), detail.group().id(),
                LlmStage.SKILL_EXEC, "SKILL_DISTILL",
                SKILL_SYSTEM_PROMPT, prompt, user);
        SkillDraftInput skill = parseSkillOutput(output, detail, slice);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into test_skill_template(project_id, scope, skill_name, applicable_scene, flow_steps,
                  source_type, source_id, status, review_status, trust_level,
                  deprecated_reason, is_vectorized, created_by, created_at, updated_at)
                values (?, 'PERSONAL', ?, ?, ?, ?, ?, 'DRAFT', 'PENDING', 'AI_GENERATED',
                  null, 0, ?, ?, ?)
                """, detail.group().projectId(), skill.skillName(), skill.applicableScene(), skill.flowSteps(),
                slice.scopeType(), slice.scopeId(), user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getSkillTemplate(id);
    }

    public List<TestSkillTemplateRecord> listSkillTemplates(Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = groupService.getById(groupId, user);
        return jdbc.sql("""
                select * from test_skill_template
                where project_id = :projectId
                  and (
                    (source_type = 'TRACE_GROUP' and source_id = :groupId)
                    or source_id in (select id from browser_trace_session where trace_group_id = :groupId)
                    or source_id in (select id from browser_issue_clip where trace_group_id = :groupId)
                  )
                order by id desc
                """).param("projectId", group.projectId()).param("groupId", groupId)
                .query(this::mapSkillTemplate).list();
    }

    @Transactional
    public List<TestToolTemplateRecord> generateToolTemplates(Long groupId, GenerateAssetCommand command, CurrentUser user) {
        TraceDataService.TraceGroupDetail detail = traceDataService.detail(groupId, user);
        TraceSlice slice = buildSlice(detail, command.traceSessionId(), command.issueClipId(), user);
        String prompt = buildToolPrompt(detail, slice);
        String output = invokeLlm(command.modelConfigId(),
                detail.group().projectId(), detail.group().id(),
                LlmStage.SKILL_EXEC, "TOOL_DISTILL",
                TOOL_SYSTEM_PROMPT, prompt, user);
        List<ToolDraftInput> tools = parseToolOutput(output, detail, slice);
        LocalDateTime now = timeProvider.now();
        List<TestToolTemplateRecord> result = new ArrayList<>();
        for (ToolDraftInput tool : tools) {
            jdbcTemplate.update("""
                    insert into test_tool_template(project_id, scope, tool_name, operation_steps, source_type, source_id,
                      status, review_status, trust_level, deprecated_reason, is_vectorized, created_by, created_at, updated_at)
                    values (?, 'PERSONAL', ?, ?, ?, ?, 'DRAFT', 'PENDING', 'AI_GENERATED', null, 0, ?, ?, ?)
                    """, detail.group().projectId(), tool.toolName(), tool.operationSteps(),
                    slice.scopeType(), slice.scopeId(), user.id(), now, now);
            Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
            result.add(getToolTemplate(id));
        }
        return result;
    }

    public List<TestToolTemplateRecord> listToolTemplates(Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = groupService.getById(groupId, user);
        return jdbc.sql("""
                select * from test_tool_template
                where project_id = :projectId
                  and (
                    (source_type = 'TRACE_GROUP' and source_id = :groupId)
                    or source_id in (select id from browser_trace_session where trace_group_id = :groupId)
                    or source_id in (select id from browser_issue_clip where trace_group_id = :groupId)
                  )
                order by id desc
                """).param("projectId", group.projectId()).param("groupId", groupId)
                .query(this::mapToolTemplate).list();
    }

    public List<TestCaseAssetView> listFormalCases(Long projectId, CurrentUser user) {
        return jdbc.sql("""
                select * from test_case_asset
                where project_id = :projectId
                order by id desc
                """).param("projectId", projectId).query(this::mapTestCaseAsset).list();
    }

    /** Library rows omit long case bodies; fetch a single record only when the detail drawer opens. */
    public FormalCasePage listFormalCasePage(Long projectId, int page, int size, String keyword,
                                             List<String> modules, List<String> priorities, List<String> statuses,
                                             List<String> sources, List<String> scenarioTypes, CurrentUser user) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        int offset = safePage * safeSize;
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        StringBuilder where = new StringBuilder(" WHERE project_id = :projectId");
        appendFormalCaseFilters(where, params, keyword, modules, priorities, statuses, sources, scenarioTypes);
        Integer total = jdbc.sql("SELECT COUNT(*) FROM test_case_asset" + where)
                .params(params).query(Integer.class).single();
        params.put("limit", safeSize);
        params.put("offset", offset);
        List<TestCaseAssetView> items = jdbc.sql(buildFormalCasePageSql(where.toString()))
                .params(params)
                .query(this::mapTestCaseAsset).list();
        List<String> moduleOptions = jdbc.sql("""
                SELECT DISTINCT module_name FROM test_case_asset
                WHERE project_id = :projectId AND module_name IS NOT NULL AND module_name <> ''
                ORDER BY module_name
                """).param("projectId", projectId).query(String.class).list();
        return new FormalCasePage(items, total == null ? 0 : total, safePage, safeSize, moduleOptions);
    }

    String buildFormalCasePageSql(String whereSql) {
        String projection = """
                SELECT id, project_id, case_no, case_title, module_name,
                       NULL AS precondition, NULL AS steps, NULL AS expected_result,
                       priority, case_type, scenario_type, design_method, case_scope, case_status,
                       submitted_by, submitted_at, exported_at,
                       source_trace_group_id, source_trace_session_id, source_issue_clip_id, created_at, updated_at
                FROM test_case_asset
                """;
        return String.join("\n",
                projection.stripTrailing(),
                whereSql.trim(),
                "ORDER BY updated_at DESC, id DESC",
                "LIMIT :limit OFFSET :offset");
    }

    void appendFormalCaseFilters(StringBuilder sql, Map<String, Object> params,
                                 String keyword, List<String> modules, List<String> priorities,
                                 List<String> statuses, List<String> sources, List<String> scenarioTypes) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (case_title LIKE :keyword OR case_no LIKE :keyword OR module_name LIKE :keyword")
                    .append(" OR case_type LIKE :keyword OR case_scope LIKE :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        appendCaseAssetInFilter(sql, params, "module_name", "modules", modules);
        appendCaseAssetInFilter(sql, params, "priority", "priorities", priorities);
        appendCaseAssetInFilter(sql, params, "scenario_type", "scenarioTypes", scenarioTypes);
        List<String> normalizedStatuses = statuses == null ? new ArrayList<>() : new ArrayList<>(statuses);
        normalizedStatuses.removeIf(value -> value == null || value.isBlank());
        if (normalizedStatuses.remove("ACTIVE")) {
            normalizedStatuses.add("ACTIVE");
            normalizedStatuses.add("SUBMITTED");
        }
        appendCaseAssetInFilter(sql, params, "case_status", "statuses", normalizedStatuses);
        List<String> normalizedSources = sources == null ? List.of() : sources.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        if (!normalizedSources.isEmpty()) {
            List<String> conditions = new ArrayList<>();
            if (normalizedSources.contains("TRACE")) {
                conditions.add("(source_trace_group_id IS NOT NULL OR source_trace_session_id IS NOT NULL OR source_issue_clip_id IS NOT NULL)");
            }
            if (normalizedSources.contains("GENERATION") || normalizedSources.contains("MANUAL")) {
                conditions.add("(source_trace_group_id IS NULL AND source_trace_session_id IS NULL AND source_issue_clip_id IS NULL)");
            }
            if (!conditions.isEmpty()) sql.append(" AND (").append(String.join(" OR ", conditions)).append(")");
        }
    }

    private void appendCaseAssetInFilter(StringBuilder sql, Map<String, Object> params, String column,
                                         String parameter, List<String> values) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        if (!normalized.isEmpty()) {
            sql.append(" AND ").append(column).append(" IN (:").append(parameter).append(")");
            params.put(parameter, normalized);
        }
    }

    public TestCaseAssetView getFormalCase(Long projectId, Long caseId, CurrentUser user) {
        List<TestCaseAssetView> rows = jdbc.sql("SELECT * FROM test_case_asset WHERE id = :id AND project_id = :projectId")
                .param("id", caseId).param("projectId", projectId).query(this::mapTestCaseAsset).list();
        if (rows.isEmpty()) throw new BusinessException("正式用例不存在");
        return rows.get(0);
    }

    @Transactional
    public void deleteFormalCase(Long projectId, Long caseId, CurrentUser user) {
        TestCaseAssetView asset = getFormalCase(projectId, caseId, user);
        boolean administrator = user != null && ("ADMIN".equals(user.roleCode()) || "SUB_ADMIN".equals(user.roleCode()));
        if (!administrator && !Objects.equals(asset.submittedBy(), user.id())) {
            throw new BusinessException("只能删除自己提交的正式用例");
        }
        int deleted = jdbcTemplate.update("DELETE FROM test_case_asset WHERE id = ? AND project_id = ?", caseId, projectId);
        if (deleted != 1) throw new BusinessException("正式用例删除失败");
    }

    @Transactional
    public GenerationCaseLibraryService.BatchOperationResult deleteFormalCases(Long projectId, List<Long> caseIds,
                                                                                 CurrentUser user) {
        List<Long> ids = caseIds == null ? List.of() : caseIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) throw new BusinessException("请至少选择一条正式用例");
        if (ids.size() > 200) throw new BusinessException("一次最多批量删除 200 条正式用例");
        for (Long caseId : ids) {
            deleteFormalCase(projectId, caseId, user);
        }
        return new GenerationCaseLibraryService.BatchOperationResult(ids.size());
    }

    @Transactional
    public TestCaseAssetView submitGeneratedCase(Long generatedCaseId, CurrentUser user) {
        TraceGeneratedCaseRecord draft = getTraceGeneratedCase(generatedCaseId);
        if (!Objects.equals(draft.userId(), user.id())) {
            throw new BusinessException("只能提交自己的轨迹用例草稿");
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title, project_name,
                  module_name, precondition, steps, expected_result, priority, case_type, scenario_type, design_method, source_refs_json,
                  created_at, updated_at, case_scope, case_status, submitted_by, submitted_at, source_trace_group_id,
                  source_trace_session_id, source_issue_clip_id)
                values (?, null, null, ?, ?, null, ?, ?, ?, ?, ?, ?, 'POSITIVE', '轨迹回放法', ?, ?, ?, 'PROJECT', 'SUBMITTED',
                  ?, ?, ?, ?, ?)
                """, draft.projectId(), "TRACE-" + draft.id(), draft.caseTitle(), draft.moduleName(), draft.precondition(),
                draft.steps(), draft.expectedResult(), draft.priority(), draft.caseType(), draft.sourceRefsJson(),
                now, now, user.id(), now, draft.traceGroupId(), draft.traceSessionId(), draft.issueClipId());
        Long assetId = jdbc.sql("select last_insert_id()").query(Long.class).single();

        jdbcTemplate.update("""
                update trace_generated_case
                set case_scope = 'PROJECT', case_status = 'SUBMITTED', updated_at = ?
                where id = ? and user_id = ?
                """, now, generatedCaseId, user.id());
        return getFormalCase(assetId);
    }

    private TraceSlice buildSlice(TraceDataService.TraceGroupDetail detail, Long traceSessionId, Long issueClipId, CurrentUser user) {
        List<BrowserTraceEventRecord> events = detail.events();
        List<BrowserTraceNetworkRecord> networks = detail.networks();
        String scopeType = "TRACE_GROUP";
        Long scopeId = detail.group().id();

        if (traceSessionId != null) {
            sessionService.getById(traceSessionId, user);
            events = events.stream().filter(item -> Objects.equals(item.traceSessionId(), traceSessionId)).toList();
            networks = networks.stream().filter(item -> Objects.equals(item.traceSessionId(), traceSessionId)).toList();
            scopeType = "TRACE_SESSION";
            scopeId = traceSessionId;
        }
        if (issueClipId != null) {
            BrowserIssueClipRecord clip = detail.issueClips().stream()
                    .filter(item -> Objects.equals(item.id(), issueClipId))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("问题片段不存在"));
            events = events.stream().filter(item -> matchClip(item, clip)).toList();
            networks = networks.stream().filter(item -> matchClip(item, clip)).toList();
            scopeType = "ISSUE_CLIP";
            scopeId = issueClipId;
        }
        return new TraceSlice(scopeType, scopeId, events, networks);
    }

    private boolean matchClip(BrowserTraceEventRecord item, BrowserIssueClipRecord clip) {
        if (clip.traceSessionId() != null && !Objects.equals(item.traceSessionId(), clip.traceSessionId())) {
            return false;
        }
        return item.relativeMs() >= clip.clipStartRelativeMs() && item.relativeMs() <= clip.clipEndRelativeMs();
    }

    private boolean matchClip(BrowserTraceNetworkRecord item, BrowserIssueClipRecord clip) {
        if (clip.traceSessionId() != null && !Objects.equals(item.traceSessionId(), clip.traceSessionId())) {
            return false;
        }
        long relativeMs = item.relativeMs() == null ? 0L : item.relativeMs();
        return relativeMs >= clip.clipStartRelativeMs() && relativeMs <= clip.clipEndRelativeMs();
    }

    private String buildSummaryPrompt(TraceDataService.TraceGroupDetail detail, TraceSlice slice) {
        List<TraceStepNormalizer.CleanTraceStep> cleanSteps = buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks());
        String semanticContext = buildSemanticContext(detail.group().projectId(), cleanSteps, slice.events());
        return """
                【范围】
                %s

                【采集组】
                %s

                【项目页面画像参考】
                %s

                %s

                【清洗后操作步骤】
                %s

                【网络摘要】
                %s
                """.formatted(
                slice.scopeType(),
                detail.group().groupName(),
                buildPageScanContext(detail.group().projectId(), slice.events()),
                semanticContext,
                buildCleanStepTimeline(cleanSteps),
                buildNetworkTimeline(slice.networks()));
    }

    private String buildCasePrompt(String caseType,
                                   TraceDataService.TraceGroupDetail detail,
                                   TraceSlice slice,
                                   List<TraceCorrectionCandidateRecord> confirmedCorrections) {
        List<TraceStepNormalizer.CleanTraceStep> cleanSteps = buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks());
        cleanSteps = correctionService.applyLearnedPatternsToCleanSteps(detail.group().projectId(), cleanSteps, slice.events());
        cleanSteps = correctionService.applyConfirmedToCleanSteps(cleanSteps, confirmedCorrections);
        cleanSteps = correctionService.applyStepCorrectionsToCleanSteps(cleanSteps, confirmedCorrections);
        String semanticContext = buildSemanticContext(detail.group().projectId(), cleanSteps, slice.events());
        return """
                【用例类型】
                %s

                【采集组】
                %s

                【项目页面画像参考】
                %s

                %s

                【真实业务步骤】
                %s

                【关键网络观察】
                %s

                【输出要求补充】
                1. 生成标准功能测试用例，不要生成自动化脚本。
                2. 只能写页面操作、字段输入、按钮点击、页面跳转、业务结果。
                3. 不要出现 DOM 选择器、技术定位语法、测试框架方法名。
                4. 如果某一步仅能确认“打开页面/输入字段/点击按钮/跳转页面”，就按这个粒度输出，不要臆造业务规则。
                """.formatted(
                caseType == null || caseType.isBlank() ? "操作路径用例" : caseType,
                detail.group().groupName(),
                buildPageScanContext(detail.group().projectId(), slice.events()),
                semanticContext,
                buildCleanStepTimeline(cleanSteps),
                buildNetworkTimeline(slice.networks()));
    }

    private String buildSkillPrompt(TraceDataService.TraceGroupDetail detail, TraceSlice slice) {
        List<TraceStepNormalizer.CleanTraceStep> cleanSteps = buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks());
        String semanticContext = buildSemanticContext(detail.group().projectId(), cleanSteps, slice.events());
        return """
                【采集组】
                %s

                【项目页面画像参考】
                %s

                %s

                【真实业务步骤】
                %s

                【关键网络观察】
                %s

                【输出要求补充】
                1. 提炼一个可复用测试 Skill。
                2. 强调适用场景、角色切换、关键动作顺序。
                3. 不要输出代码，不要输出自动化框架术语。
                """.formatted(
                detail.group().groupName(),
                buildPageScanContext(detail.group().projectId(), slice.events()),
                semanticContext,
                buildCleanStepTimeline(cleanSteps),
                buildNetworkTimeline(slice.networks()));
    }

    private String buildToolPrompt(TraceDataService.TraceGroupDetail detail, TraceSlice slice) {
        List<TraceStepNormalizer.CleanTraceStep> cleanSteps = buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks());
        String semanticContext = buildSemanticContext(detail.group().projectId(), cleanSteps, slice.events());
        return """
                【采集组】
                %s

                【项目页面画像参考】
                %s

                %s

                【真实业务步骤】
                %s

                【输出要求补充】
                1. 按可复用动作拆成 1 到 5 个 Tool。
                2. 每个 Tool 聚焦一个明确操作，例如“登录系统”“提交申请”“审批申请”。
                3. 不要输出自动化脚本，不要输出技术定位语法。
                """.formatted(
                detail.group().groupName(),
                buildPageScanContext(detail.group().projectId(), slice.events()),
                semanticContext,
                buildCleanStepTimeline(cleanSteps));
    }

    private String buildEventTimeline(List<BrowserTraceEventRecord> events) {
        if (events.isEmpty()) {
            return "无操作事件";
        }
        Map<Long, String> profileNames = loadProfileNames(events.stream()
                .map(BrowserTraceEventRecord::profileId).distinct().toList());
        return events.stream()
                .limit(120)
                .map(event -> {
                    String actor = profileNames.getOrDefault(event.profileId(), "身份空间#" + event.profileId());
                    return "[%s][+%dms][%s] %s %s %s".formatted(
                            event.happenedAtLocal(),
                            event.relativeMs(),
                            actor,
                            event.eventType(),
                            safe(event.pageTitle(), event.pageUrl()),
                            safe(event.elementText(), event.valueSummary()));
                })
                .collect(Collectors.joining("\n"));
    }

    private List<TraceStepNormalizer.CleanTraceStep> buildCleanSteps(Long projectId, List<BrowserTraceEventRecord> events) {
        return buildCleanSteps(projectId, events, List.of());
    }

    private List<TraceStepNormalizer.CleanTraceStep> buildCleanSteps(Long projectId,
                                                                     List<BrowserTraceEventRecord> events,
                                                                     List<BrowserTraceNetworkRecord> networks) {
        Map<Long, String> profileNames = loadProfileNames(events.stream()
                .map(BrowserTraceEventRecord::profileId).distinct().toList());
        Map<String, ControlledScanService.PageScanHint> pageHints =
                controlledScanService.buildHintIndex(projectId, events.stream().map(BrowserTraceEventRecord::pageUrl).toList());
        return traceStepNormalizer.normalize(projectId, events, networks, profileNames, pageHints);
    }

    private String buildPageScanContext(Long projectId, List<BrowserTraceEventRecord> events) {
        return controlledScanService.buildPromptContext(projectId,
                events.stream().map(BrowserTraceEventRecord::pageUrl).filter(Objects::nonNull).toList());
    }

    private String buildSemanticContext(Long projectId,
                                        List<TraceStepNormalizer.CleanTraceStep> cleanSteps,
                                        List<BrowserTraceEventRecord> events) {
        String focusText = cleanSteps.stream()
                .limit(24)
                .map(TraceStepNormalizer.CleanTraceStep::description)
                .collect(Collectors.joining("\n"));
        return semanticContextService.build(projectId, focusText,
                events.stream().map(BrowserTraceEventRecord::pageUrl).filter(Objects::nonNull).toList(), 8)
                .promptSection();
    }

    private String buildCleanStepTimeline(List<TraceStepNormalizer.CleanTraceStep> steps) {
        if (steps.isEmpty()) {
            return "无可用业务步骤";
        }
        return steps.stream()
                .limit(80)
                .map(step -> "%d. 【%s】%s".formatted(step.stepNo(), step.actor(), step.description()))
                .collect(Collectors.joining("\n"));
    }

    private String buildNetworkTimeline(List<BrowserTraceNetworkRecord> networks) {
        if (networks.isEmpty()) {
            return "无网络记录";
        }
        List<String> observations = traceStepNormalizer.buildNetworkObservations(networks);
        if (!observations.isEmpty()) {
            return String.join("\n", observations);
        }
        return networks.stream()
                .limit(20)
                .map(item -> "[+%dms] %s %s status=%s duration=%sms".formatted(
                        item.relativeMs() == null ? 0L : item.relativeMs(),
                        safe(item.method(), "GET"),
                        item.url(),
                        item.statusCode() == null ? "-" : item.statusCode(),
                        item.durationMs() == null ? "-" : item.durationMs()))
                .collect(Collectors.joining("\n"));
    }

    private Map<Long, String> loadProfileNames(List<Long> profileIds) {
        List<Long> ids = profileIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String inClause = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.sql("select id, profile_name from browser_profile where id in (" + inClause + ")")
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("profileName", rs.getString("profile_name"));
                    return row;
                }).list();
        Map<Long, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((Long) row.get("id"), (String) row.get("profileName"));
        }
        return result;
    }

    private List<CaseDraftInput> parseCaseOutput(String rawOutput, Long groupId, List<CaseDraftInput> fallbackCases) {
        String jsonText = extractJson(rawOutput);
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(jsonText, new TypeReference<>() {
            });
            List<CaseDraftInput> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(new CaseDraftInput(
                        str(row.get("caseTitle"), "轨迹用例-" + groupId),
                        str(row.get("moduleName"), "默认模块"),
                        str(row.get("precondition"), ""),
                        str(row.get("steps"), ""),
                        str(row.get("expectedResult"), ""),
                        str(row.get("priority"), "P2")
                ));
            }
            result = result.stream().map(this::sanitizeCaseDraft).toList();
            if (result.isEmpty() || result.stream().allMatch(this::containsAutomationVocabulary)) {
                throw new BusinessException("模型未返回可用轨迹用例");
            }
            return result;
        } catch (JsonProcessingException | BusinessException ex) {
            return fallbackCases;
        }
    }

    private List<CaseDraftInput> buildFallbackCases(String caseType,
                                                    TraceDataService.TraceGroupDetail detail,
                                                    TraceSlice slice,
                                                    List<TraceCorrectionCandidateRecord> confirmedCorrections) {
        List<TraceStepNormalizer.CleanTraceStep> steps = buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks());
        steps = correctionService.applyLearnedPatternsToCleanSteps(detail.group().projectId(), steps, slice.events());
        steps = correctionService.applyConfirmedToCleanSteps(steps, confirmedCorrections);
        steps = correctionService.applyStepCorrectionsToCleanSteps(steps, confirmedCorrections);
        if (steps.isEmpty()) {
            return List.of(new CaseDraftInput(
                    "轨迹操作待补充",
                    detail.group().groupName(),
                    "已打开对应业务页面",
                    "1. 请重新执行一次关键操作轨迹并采集。\n2. 采集完成后重新生成测试用例草稿。",
                    "能够采集到完整的功能操作轨迹，并生成标准功能测试用例。",
                    "P2"
            ));
        }
        String stepText = steps.stream()
                .map(step -> "%d. 【%s】%s".formatted(step.stepNo(), step.actor(), step.description()))
                .collect(Collectors.joining("\n"));
        List<String> observations = traceStepNormalizer.buildNetworkObservations(slice.networks());
        String expected = observations.isEmpty()
                ? "页面按轨迹继续流转，相关操作结果与实际采集结果保持一致。"
                : "页面按轨迹继续流转；同时关注以下异常或接口观察：\n- " + String.join("\n- ", observations);
        return List.of(new CaseDraftInput(
                (caseType == null || caseType.isBlank() ? "操作路径用例" : caseType) + "-" + detail.group().groupName(),
                detail.group().groupName(),
                "已进入轨迹涉及的起始页面，并准备好对应身份空间。",
                stepText,
                expected,
                "P2"
        ));
    }

    private CaseDraftInput sanitizeCaseDraft(CaseDraftInput input) {
        return new CaseDraftInput(
                stripAutomationVocabulary(input.caseTitle()),
                stripAutomationVocabulary(input.moduleName()),
                stripAutomationVocabulary(input.precondition()),
                stripAutomationVocabulary(input.steps()),
                stripAutomationVocabulary(input.expectedResult()),
                input.priority()
        );
    }

    private boolean containsAutomationVocabulary(CaseDraftInput input) {
        return containsAutomationVocabulary(input.steps())
                || containsAutomationVocabulary(input.expectedResult())
                || containsAutomationVocabulary(input.caseTitle());
    }

    private boolean containsAutomationVocabulary(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("locator")
                || lower.contains("xpath")
                || lower.contains("selector")
                || lower.contains("click()")
                || lower.contains("fill()")
                || lower.contains("press()")
                || lower.contains("page.")
                || lower.contains("playwright")
                || lower.contains("selenium")
                || lower.contains("cypress");
    }

    private String stripAutomationVocabulary(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return text
                .replaceAll("(?i)playwright|selenium|cypress", "浏览器操作")
                .replaceAll("(?i)locator|xpath|selector", "页面元素")
                .replaceAll("(?i)click\\(\\)|fill\\(\\)|press\\(\\)|page\\.", "")
                .trim();
    }

    private String buildPromptSnapshot(String systemPrompt, String userPrompt) {
        return systemPrompt + "\n\n---\n\n" + userPrompt;
    }

    private SkillDraftInput parseSkillOutput(String rawOutput, TraceDataService.TraceGroupDetail detail, TraceSlice slice) {
        String jsonText = extractJson(rawOutput);
        try {
            Map<String, Object> row = objectMapper.readValue(jsonText, new TypeReference<>() {
            });
            return new SkillDraftInput(
                    str(row.get("skillName"), detail.group().groupName() + "-流程测试 Skill"),
                    str(row.get("applicableScene"), "基于真实轨迹提炼的流程测试场景"),
                    stripAutomationVocabulary(str(row.get("flowSteps"),
                            buildCleanStepTimeline(buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks()))))
            );
        } catch (JsonProcessingException ex) {
            return new SkillDraftInput(
                    detail.group().groupName() + "-流程测试 Skill",
                    "基于真实轨迹提炼的流程测试场景",
                    buildCleanStepTimeline(buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks()))
            );
        }
    }

    private List<ToolDraftInput> parseToolOutput(String rawOutput, TraceDataService.TraceGroupDetail detail, TraceSlice slice) {
        String jsonText = extractJson(rawOutput);
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(jsonText, new TypeReference<>() {
            });
            List<ToolDraftInput> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(new ToolDraftInput(
                        str(row.get("toolName"), detail.group().groupName() + "-操作 Tool"),
                        stripAutomationVocabulary(str(row.get("operationSteps"),
                                buildCleanStepTimeline(buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks()))))
                ));
            }
            return result.isEmpty() ? List.of(fallbackTool(detail, slice)) : result;
        } catch (JsonProcessingException ex) {
            return List.of(fallbackTool(detail, slice));
        }
    }

    private ToolDraftInput fallbackTool(TraceDataService.TraceGroupDetail detail, TraceSlice slice) {
        return new ToolDraftInput(
                detail.group().groupName() + "-关键操作 Tool",
                buildCleanStepTimeline(buildCleanSteps(detail.group().projectId(), slice.events(), slice.networks()))
        );
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "[]";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        return text;
    }

    private String normalizePriority(String value) {
        if (value == null) return "P2";
        String v = value.trim().toUpperCase();
        return switch (v) {
            case "P0", "P1", "P2", "P3", "P4" -> v;
            default -> "P2";
        };
    }

    private String str(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private String safe(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback == null ? "" : fallback;
    }

    private String buildTraceSourceRefsJson(Long groupId, Long sessionId, Long issueClipId) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("source", "TRACE");
        refs.put("traceGroupId", groupId);
        refs.put("traceSessionId", sessionId);
        refs.put("issueClipId", issueClipId);
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            return "{\"source\":\"TRACE\"}";
        }
    }

    private KnowledgeAssetRecord getKnowledgeAsset(Long id) {
        return jdbc.sql("select * from knowledge_asset where id = :id")
                .param("id", id).query(this::mapKnowledgeAsset).single();
    }

    private TraceGeneratedCaseRecord getTraceGeneratedCase(Long id) {
        return jdbc.sql("select * from trace_generated_case where id = :id")
                .param("id", id).query(this::mapTraceGeneratedCase).single();
    }

    private TestCaseAssetView getFormalCase(Long id) {
        return jdbc.sql("select * from test_case_asset where id = :id")
                .param("id", id).query(this::mapTestCaseAsset).single();
    }

    private TestSkillTemplateRecord getSkillTemplate(Long id) {
        return jdbc.sql("select * from test_skill_template where id = :id")
                .param("id", id).query(this::mapSkillTemplate).single();
    }

    private TestToolTemplateRecord getToolTemplate(Long id) {
        return jdbc.sql("select * from test_tool_template where id = :id")
                .param("id", id).query(this::mapToolTemplate).single();
    }

    private TraceGeneratedCaseRecord mapTraceGeneratedCase(ResultSet rs, int rowNum) throws SQLException {
        return new TraceGeneratedCaseRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("user_id"),
                getLongNullable(rs, "trace_group_id"),
                getLongNullable(rs, "trace_session_id"),
                getLongNullable(rs, "issue_clip_id"),
                rs.getString("case_type"),
                rs.getString("case_title"),
                rs.getString("module_name"),
                rs.getString("precondition"),
                rs.getString("steps"),
                rs.getString("expected_result"),
                rs.getString("priority"),
                rs.getString("case_scope"),
                rs.getString("case_status"),
                rs.getString("source_refs_json"),
                getLongNullable(rs, "model_config_id"),
                rs.getString("prompt_snapshot"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private KnowledgeAssetRecord mapKnowledgeAsset(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeAssetRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("asset_type"),
                rs.getString("asset_ref_type"),
                rs.getLong("asset_ref_id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getString("visibility"),
                getLongNullable(rs, "created_by"),
                getLongNullable(rs, "deprecated_by"),
                toLocalDateTime(rs, "deprecated_at"),
                rs.getString("deprecated_reason"),
                rs.getString("deprecated_note"),
                getIntegerNullable(rs, "is_vectorized"),
                rs.getString("vector_id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private TestCaseAssetView mapTestCaseAsset(ResultSet rs, int rowNum) throws SQLException {
        return new TestCaseAssetView(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("case_no"),
                rs.getString("case_title"),
                rs.getString("module_name"),
                rs.getString("precondition"),
                rs.getString("steps"),
                rs.getString("expected_result"),
                rs.getString("priority"),
                rs.getString("case_type"),
                rs.getString("scenario_type"),
                rs.getString("design_method"),
                rs.getString("case_scope"),
                rs.getString("case_status"),
                getLongNullable(rs, "submitted_by"),
                toLocalDateTime(rs, "submitted_at"),
                toLocalDateTime(rs, "exported_at"),
                getLongNullable(rs, "source_trace_group_id"),
                getLongNullable(rs, "source_trace_session_id"),
                getLongNullable(rs, "source_issue_clip_id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private TestSkillTemplateRecord mapSkillTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new TestSkillTemplateRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("skill_name"),
                rs.getString("applicable_scene"),
                rs.getString("flow_steps"),
                rs.getString("source_type"),
                getLongNullable(rs, "source_id"),
                rs.getString("status"),
                rs.getString("deprecated_reason"),
                getIntegerNullable(rs, "is_vectorized"),
                getLongNullable(rs, "created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private TestToolTemplateRecord mapToolTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new TestToolTemplateRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("tool_name"),
                rs.getString("operation_steps"),
                rs.getString("source_type"),
                getLongNullable(rs, "source_id"),
                rs.getString("status"),
                rs.getString("deprecated_reason"),
                getIntegerNullable(rs, "is_vectorized"),
                getLongNullable(rs, "created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private Integer getIntegerNullable(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long getLongNullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record GenerateSummaryCommand(Long modelConfigId, Long traceSessionId, Long issueClipId) {
    }

    public record GenerateCasesCommand(Long modelConfigId, String caseType, Long traceSessionId, Long issueClipId) {
    }

    public record GenerateAssetCommand(Long modelConfigId, Long traceSessionId, Long issueClipId) {
    }

    public record CleanStepView(int stepNo, String actor, String actionType, String description,
                                String pageName, String pageUrl, Long relativeMs) {
    }

    private record TraceSlice(String scopeType, Long scopeId, List<BrowserTraceEventRecord> events,
                              List<BrowserTraceNetworkRecord> networks) {
    }

    private record CaseDraftInput(String caseTitle, String moduleName, String precondition, String steps,
                                  String expectedResult, String priority) {
    }

    private record SkillDraftInput(String skillName, String applicableScene, String flowSteps) {
    }

    private record ToolDraftInput(String toolName, String operationSteps) {
    }

    public record TestCaseAssetView(Long id, Long projectId, String caseNo, String caseTitle, String moduleName,
                                    String precondition, String steps, String expectedResult, String priority,
                                    String caseType, String scenarioType, String designMethod,
                                    String caseScope, String caseStatus, Long submittedBy,
                                    LocalDateTime submittedAt, LocalDateTime exportedAt, Long sourceTraceGroupId,
                                    Long sourceTraceSessionId, Long sourceIssueClipId, LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {
    }

    public record FormalCasePage(List<TestCaseAssetView> items, int total, int page, int size,
                                 List<String> moduleOptions) {
    }

    public record GeneratedCasePage(List<TraceGeneratedCaseRecord> items, int total, int page, int size) {
    }

    /**
     * 统一封装：把 LlmAdapter 直调改成 LlmGateway 调用，并在调用失败时抛 BusinessException。
     * stage / taskType 与方案 §4.2 对齐。
     */
    private String invokeLlm(Long modelConfigId, Long projectId, Long taskId,
                             LlmStage stage, String taskType,
                             String systemPrompt, String userPrompt, CurrentUser user) {
        if (user == null || user.id() == null) {
            throw new com.company.aitest.common.BusinessException("缺少调用用户上下文");
        }
        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, taskId,
                taskType, stage,
                modelConfigId, null, null, java.util.Map.of(),
                systemPrompt, userPrompt, taskId));
        if (resp.status() != LlmInvocationStatus.OK) {
            throw new com.company.aitest.common.BusinessException(
                    "模型调用失败：" + (resp.errorMessage() == null ? resp.status().name() : resp.errorMessage()));
        }
        return resp.content();
    }
}
