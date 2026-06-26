package com.company.aitest.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
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

/**
 * 轨迹摘要正式承载层服务。
 * <p>
 * 摘要主链路不再走 knowledge_asset，统一落入 trace_summary。
 * 生成时必须写 prompt_snapshot_id / context_manifest_id / llm_invocation_log_id。
 * 默认状态 DRAFT，只有 CONFIRMED 才能进入 Mini-TOM 候选。
 *
 * @see docs/handover/10_轨迹摘要文本化P0设计与实现方案.md §8
 */
@Service
public class TraceSummaryService {

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是资深测试分析师。请基于真实测试执行轨迹，输出结构化摘要。
            要求：
            1. 不要虚构未发生的步骤。
            2. 明确区分操作路径、关键页面、角色切换、网络异常和可复用测试价值。
            3. 用中文输出，适合沉淀为测试资产摘要。
            4. 必须按以下 JSON 结构输出，不要输出 markdown 或额外解释：

            {
              "overview": "用1句话说明本次轨迹记录的业务流程",
              "businessSummary": ["描述用户完成的业务动作", "涉及角色、页面和结果"],
              "keySteps": ["步骤1描述", "步骤2描述"],
              "keyApis": [
                {"method": "POST", "url": "/api/xxx", "status": 200, "remark": "关键接口说明"}
              ],
              "exceptionSummary": "关键接口异常说明；若无明显异常输出'未发现明显异常请求'",
              "caseGenerationSuggestion": {
                "fit": ["适合生成的用例类型"],
                "unfit": ["不适合生成的用例类型"],
                "reasons": ["原因说明"]
              },
              "pendingConfirmation": [
                {"type": "OBJECT_LABEL|ACTION_SEMANTICS", "field": "", "value": "", "reason": ""}
              ]
            }

            5. 不输出机械点击流水，转成业务动作描述。
            6. 若对象或动作不够确定，保守表达并记录到 pendingConfirmation。
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
    private final com.company.aitest.loop.LoopIntegrationService loopIntegrationService;

    public TraceSummaryService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                               TraceDataService traceDataService, BrowserTraceGroupService groupService,
                               BrowserTraceSessionService sessionService,
                               LlmGateway llmGateway,
                               ControlledScanService controlledScanService,
                               TraceCorrectionSuggestionService correctionService,
                               ProjectSemanticContextService semanticContextService,
                               List<TraceRulePack> traceRulePacks,
                               com.company.aitest.loop.LoopIntegrationService loopIntegrationService) {
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
        this.loopIntegrationService = loopIntegrationService;
        this.objectMapper = new ObjectMapper();
        this.traceStepNormalizer = new TraceStepNormalizer(this.traceRulePacks);
    }

    /**
     * 生成摘要草稿。默认状态 DRAFT，必须写 LLM 快照引用。
     */
    @Transactional
    public TraceSummaryRecord generateSummary(Long groupId, GenerateSummaryCommand command, CurrentUser user) {
        TraceDataService.TraceGroupDetail detail = traceDataService.detail(groupId, user);
        TraceSlice slice = buildSlice(detail, command.traceSessionId(), command.issueClipId(), user);
        String prompt = buildSummaryPrompt(detail, slice);

        LlmInvocationResponse resp = invokeLlmGateway(command.modelConfigId(),
                detail.group().projectId(), detail.group().id(),
                LlmStage.TRACE_SUMMARY, "TRACE_SUMMARY",
                SUMMARY_SYSTEM_PROMPT, prompt, user);

        String output = resp.content();
        if (output == null || output.isBlank()) {
            output = "{}";
        }

        SummaryOutput parsed = parseSummaryOutput(output);
        LocalDateTime now = timeProvider.now();

        jdbcTemplate.update("""
                insert into trace_summary(
                    project_id, trace_group_id, trace_session_id, issue_clip_id, summary_scope,
                    overview, business_summary, key_steps_json, key_api_json, exception_summary,
                    case_generation_suggestion_json, raw_input_snapshot, llm_output_snapshot,
                    model_config_id, prompt_snapshot_id, context_manifest_id, llm_invocation_log_id,
                    status, validity_label, summary_text,
                    created_by, pending_confirmation_json, confidence_label,
                    scope, review_status, trust_level,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 'TO_CONFIRM', ?,
                    ?, ?, 'MEDIUM', 'PERSONAL', 'PENDING', 'AI_GENERATED', ?, ?)
                """,
                detail.group().projectId(), groupId, command.traceSessionId(), command.issueClipId(), slice.scopeType(),
                parsed.overview(), parsed.businessSummary(), parsed.keyStepsJson(), parsed.keyApiJson(), parsed.exceptionSummary(),
                parsed.caseGenerationSuggestionJson(), prompt, output,
                command.modelConfigId(), resp.promptSnapshotId(), resp.contextManifestId(), resp.invocationLogId(),
                parsed.overview(),
                user.id(), parsed.pendingConfirmationJson(), now, now);

        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        var record = getSummaryById(id);

        loopIntegrationService.onTraceSummaryCompleted(detail.group().projectId(), output, prompt, user);
        loopIntegrationService.onChineseLocalizationCheck(detail.group().projectId(), output, "TRACE_SUMMARY", user);

        return record;
    }

    /**
     * 基于现有摘要的 scope 重新生成。
     */
    @Transactional
    public TraceSummaryRecord regenerateSummary(Long summaryId, Long modelConfigId, CurrentUser user) {
        TraceSummaryRecord existing = getSummaryById(summaryId);
        if (existing == null) {
            throw new BusinessException("摘要不存在");
        }
        TraceDataService.TraceGroupDetail detail = traceDataService.detail(existing.traceGroupId(), user);
        TraceSlice slice = buildSlice(detail, existing.traceSessionId(), existing.issueClipId(), user);
        String prompt = buildSummaryPrompt(detail, slice);

        LlmInvocationResponse resp = invokeLlmGateway(
                modelConfigId != null ? modelConfigId : existing.modelConfigId(),
                detail.group().projectId(), detail.group().id(),
                LlmStage.TRACE_SUMMARY, "TRACE_SUMMARY_REGENERATE",
                SUMMARY_SYSTEM_PROMPT, prompt, user);

        String output = resp.content();
        if (output == null || output.isBlank()) {
            output = "{}";
        }

        SummaryOutput parsed = parseSummaryOutput(output);
        LocalDateTime now = timeProvider.now();

        int affected = jdbcTemplate.update("""
                update trace_summary set
                    overview = ?, business_summary = ?, key_steps_json = ?, key_api_json = ?,
                    exception_summary = ?, case_generation_suggestion_json = ?,
                    raw_input_snapshot = ?, llm_output_snapshot = ?,
                    model_config_id = ?, prompt_snapshot_id = ?, context_manifest_id = ?, llm_invocation_log_id = ?,
                    status = 'DRAFT', validity_label = 'TO_CONFIRM',
                    summary_text = ?, pending_confirmation_json = ?, confidence_label = 'MEDIUM',
                    confirmed_by = null, confirmed_at = null,
                    rejected_by = null, rejected_at = null, rejected_reason = null,
                    updated_at = ?, version = version + 1
                where id = ? and version = ?
                """,
                parsed.overview(), parsed.businessSummary(), parsed.keyStepsJson(), parsed.keyApiJson(),
                parsed.exceptionSummary(), parsed.caseGenerationSuggestionJson(),
                prompt, output,
                modelConfigId != null ? modelConfigId : existing.modelConfigId(),
                resp.promptSnapshotId(), resp.contextManifestId(), resp.invocationLogId(),
                parsed.overview(), parsed.pendingConfirmationJson(), now,
                summaryId, existing.version());
        if (affected == 0) {
            throw new BusinessException("该摘要已被其他用户处理，请刷新后查看最新状态");
        }
        refreshSemanticSnapshotQuietly(existing.projectId());
        return getSummaryById(summaryId);
    }

    /**
     * 用户编辑摘要结构化内容。
     */
    @Transactional
    public TraceSummaryRecord updateSummary(Long summaryId, UpdateSummaryCommand command, CurrentUser user) {
        TraceSummaryRecord existing = getSummaryById(summaryId);
        if (existing == null) {
            throw new BusinessException("摘要不存在");
        }
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                update trace_summary set
                    overview = ?, business_summary = ?, key_steps_json = ?, key_api_json = ?,
                    exception_summary = ?, case_generation_suggestion_json = ?,
                    validity_label = ?, pending_confirmation_json = ?, updated_at = ?,
                    version = version + 1
                where id = ? and status not in ('CONFIRMED', 'REJECTED') and version = ?
                """,
                command.overview(), command.businessSummary(), command.keyStepsJson(), command.keyApiJson(),
                command.exceptionSummary(), command.caseGenerationSuggestionJson(),
                command.validityLabel(), command.pendingConfirmationJson(), now,
                summaryId, existing.version());
        if (affected == 0) {
            throw new BusinessException("该摘要已被其他用户处理或状态不允许编辑，请刷新后查看最新状态");
        }
        return getSummaryById(summaryId);
    }

    /**
     * 确认摘要有效。只有 CONFIRMED 才能进入 Mini-TOM。
     */
    @Transactional
    public TraceSummaryRecord confirmSummary(Long summaryId, String validityLabel, CurrentUser user) {
        TraceSummaryRecord existing = getSummaryById(summaryId);
        if (existing == null) {
            throw new BusinessException("摘要不存在");
        }
        LocalDateTime now = timeProvider.now();
        String label = validityLabel != null && !validityLabel.isBlank() ? validityLabel : "STANDARD";
        int affected = jdbcTemplate.update("""
                update trace_summary set
                    status = 'CONFIRMED', validity_label = ?,
                    confirmed_by = ?, confirmed_at = ?,
                    rejected_by = null, rejected_at = null, rejected_reason = null,
                    updated_at = ?, version = version + 1
                where id = ? and status in ('DRAFT', 'REJECTED') and version = ?
                """, label, user.id(), now, now, summaryId, existing.version());
        if (affected == 0) {
            throw new BusinessException("该摘要已被其他用户处理，请刷新后查看最新状态");
        }
        return getSummaryById(summaryId);
    }

    /**
     * 驳回摘要。
     */
    @Transactional
    public TraceSummaryRecord rejectSummary(Long summaryId, String reason, CurrentUser user) {
        TraceSummaryRecord existing = getSummaryById(summaryId);
        if (existing == null) {
            throw new BusinessException("摘要不存在");
        }
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                update trace_summary set
                    status = 'REJECTED',
                    rejected_by = ?, rejected_at = ?, rejected_reason = ?,
                    confirmed_by = null, confirmed_at = null,
                    updated_at = ?, version = version + 1
                where id = ? and status in ('DRAFT', 'CONFIRMED') and version = ?
                """, user.id(), now, reason != null ? reason : "", now, summaryId, existing.version());
        if (affected == 0) {
            throw new BusinessException("该摘要已被其他用户处理，请刷新后查看最新状态");
        }
        return getSummaryById(summaryId);
    }

    /**
     * 获取单条摘要。
     */
    public TraceSummaryRecord getSummary(Long summaryId, CurrentUser user) {
        TraceSummaryRecord record = getSummaryById(summaryId);
        if (record == null) {
            throw new BusinessException("摘要不存在");
        }
        return record;
    }

    /**
     * 按 group 列出摘要，包含 session/clip 级摘要。
     */
    public List<TraceSummaryRecord> listSummaries(Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = groupService.getById(groupId, user);
        return jdbc.sql("""
                        select * from trace_summary
                        where trace_group_id = :groupId
                        order by id desc
                        """).param("groupId", groupId)
                .query(this::mapTraceSummary).list();
    }

    /**
     * 列出项目下所有 CONFIRMED 的 trace_summary（跨 group）。
     * 用于手册 TOM 与轨迹 TOM 交叉验证。
     */
    public List<TraceSummaryRecord> listConfirmedByProject(Long projectId) {
        return jdbc.sql("""
                        select * from trace_summary
                        where project_id = :projectId and status = 'CONFIRMED'
                        order by id desc
                        """).param("projectId", projectId)
                .query(this::mapTraceSummary).list();
    }

    /**
     * 准入控制：只有 CONFIRMED 的 trace_summary 才允许进入 Mini-TOM 候选。
     */
    public boolean isConfirmedForMiniTom(Long summaryId) {
        TraceSummaryRecord record = getSummaryById(summaryId);
        return record != null && "CONFIRMED".equals(record.status());
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private TraceSummaryRecord getSummaryById(Long id) {
        var results = jdbc.sql("select * from trace_summary where id = :id")
                .param("id", id).query(this::mapTraceSummary).list();
        return results.isEmpty() ? null : results.get(0);
    }

    private TraceSlice buildSlice(TraceDataService.TraceGroupDetail detail, Long traceSessionId, Long issueClipId, CurrentUser user) {
        List<BrowserTraceEventRecord> events = detail.events();
        List<BrowserTraceNetworkRecord> networks = detail.networks();
        String scopeType = "GROUP";
        Long scopeId = detail.group().id();

        if (traceSessionId != null) {
            sessionService.getById(traceSessionId, user);
            events = events.stream().filter(item -> Objects.equals(item.traceSessionId(), traceSessionId)).toList();
            networks = networks.stream().filter(item -> Objects.equals(item.traceSessionId(), traceSessionId)).toList();
            scopeType = "SESSION";
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
        cleanSteps = correctionService.applyLearnedPatternsToCleanSteps(detail.group().projectId(), cleanSteps, slice.events());
        List<TraceCorrectionCandidateRecord> confirmed = correctionService.getConfirmedForSummary(
                detail.group().id(),
                slice.scopeType().equals("SESSION") ? slice.scopeId() : null,
                slice.scopeType().equals("ISSUE_CLIP") ? slice.scopeId() : null);
        cleanSteps = correctionService.applyConfirmedToCleanSteps(cleanSteps, confirmed);
        cleanSteps = correctionService.applyStepCorrectionsToCleanSteps(cleanSteps, confirmed);
        String confirmedCorrections = correctionService.formatConfirmedForPrompt(confirmed);
        String semanticContext = buildSemanticContext(detail.group().projectId(), cleanSteps, slice.events());
        return """
                【范围】
                %s

                【采集组】
                %s

                【项目页面画像参考】
                %s

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
                confirmedCorrections,
                buildCleanStepTimeline(cleanSteps),
                buildNetworkTimeline(slice.networks()));
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

    private SummaryOutput parseSummaryOutput(String raw) {
        String jsonText = extractJson(raw);
        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonText, new TypeReference<>() {});
            String overview = str(parsed.get("overview"), "");
            String businessSummary = jsonString(parsed.get("businessSummary"));
            String keySteps = jsonString(parsed.get("keySteps"));
            String keyApis = jsonString(parsed.get("keyApis"));
            String exceptionSummary = str(parsed.get("exceptionSummary"), "");
            String caseSuggestion = jsonString(parsed.get("caseGenerationSuggestion"));
            String pendingConfirmation = jsonString(parsed.get("pendingConfirmation"));
            return new SummaryOutput(overview, businessSummary, keySteps, keyApis,
                    exceptionSummary, caseSuggestion, pendingConfirmation);
        } catch (JsonProcessingException e) {
            return new SummaryOutput(raw.substring(0, Math.min(raw.length(), 500)), "", "", "", "", "", "");
        }
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
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

    private String jsonString(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
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

    private LlmInvocationResponse invokeLlmGateway(Long modelConfigId, Long projectId, Long taskId,
                                                   LlmStage stage, String taskType,
                                                   String systemPrompt, String userPrompt, CurrentUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException("缺少调用用户上下文");
        }
        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, taskId,
                taskType, stage,
                modelConfigId, null, null, java.util.Map.of(),
                systemPrompt, userPrompt, taskId));
        if (resp.status() != LlmInvocationStatus.OK) {
            throw new BusinessException(
                    "模型调用失败：" + (resp.errorMessage() == null ? resp.status().name() : resp.errorMessage()));
        }
        return resp;
    }

    private TraceSummaryRecord mapTraceSummary(ResultSet rs, int rowNum) throws SQLException {
        return new TraceSummaryRecord(
                rs.getLong("id"),
                getLongNullable(rs, "project_id"),
                getLongNullable(rs, "trace_group_id"),
                getLongNullable(rs, "trace_session_id"),
                getLongNullable(rs, "issue_clip_id"),
                rs.getString("summary_scope"),
                rs.getString("overview"),
                rs.getString("business_summary"),
                rs.getString("key_steps_json"),
                rs.getString("key_api_json"),
                rs.getString("exception_summary"),
                rs.getString("case_generation_suggestion_json"),
                rs.getString("raw_input_snapshot"),
                rs.getString("llm_output_snapshot"),
                getLongNullable(rs, "model_config_id"),
                getLongNullable(rs, "prompt_snapshot_id"),
                getLongNullable(rs, "context_manifest_id"),
                getLongNullable(rs, "llm_invocation_log_id"),
                rs.getString("status"),
                rs.getString("validity_label"),
                getLongNullable(rs, "created_by"),
                getLongNullable(rs, "confirmed_by"),
                toLocalDateTime(rs, "confirmed_at"),
                getLongNullable(rs, "rejected_by"),
                toLocalDateTime(rs, "rejected_at"),
                rs.getString("rejected_reason"),
                getLongNullable(rs, "deprecated_by"),
                toLocalDateTime(rs, "deprecated_at"),
                rs.getString("deprecated_reason"),
                rs.getString("pending_confirmation_json"),
                rs.getString("confidence_label"),
                rs.getString("summary_text"),
                rs.getString("scope"),
                rs.getString("review_status"),
                rs.getString("trust_level"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at"),
                rs.getInt("version")
        );
    }

    private Long getLongNullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private void refreshSemanticSnapshotQuietly(Long projectId) {
        try {
            semanticContextService.refreshSnapshot(projectId);
        } catch (Exception ignored) {
            // 语义快照是附加缓存，不阻塞摘要主链路
        }
    }

    private record TraceSlice(String scopeType, Long scopeId, List<BrowserTraceEventRecord> events,
                              List<BrowserTraceNetworkRecord> networks) {
    }

    private record SummaryOutput(String overview, String businessSummary, String keyStepsJson,
                                 String keyApiJson, String exceptionSummary,
                                 String caseGenerationSuggestionJson, String pendingConfirmationJson) {
    }

    public record GenerateSummaryCommand(Long modelConfigId, Long traceSessionId, Long issueClipId, String summaryScope) {
    }

    public record UpdateSummaryCommand(String overview, String businessSummary, String keyStepsJson,
                                       String keyApiJson, String exceptionSummary,
                                       String caseGenerationSuggestionJson, String validityLabel,
                                       String pendingConfirmationJson) {
    }
}
