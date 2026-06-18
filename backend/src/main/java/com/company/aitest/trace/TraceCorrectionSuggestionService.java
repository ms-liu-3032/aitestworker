package com.company.aitest.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轨迹定位与语义修正建议服务。
 * <p>
 * 该能力只作为采集后的分析能力，目标是：
 * 1. 提升轨迹摘要文本化质量；
 * 2. 提升测试用例草稿质量；
 * 3. 为后续 Mini-TOM 候选抽取提供更干净的上游输入。
 * <p>
 * 设计边界：
 * - 不自动回放页面；
 * - 不自动修复 Playwright 脚本；
 * - 不自动替换正式 locator；
 * - 不自动写入 Mini-TOM / Weaviate / Neo4j；
 * - 所有结果默认 CANDIDATE，只有 CONFIRMED 才参与下游消费。
 *
 * @see docs/handover/11_轨迹定位与语义修正建议设计方案.md
 */
@Service
public class TraceCorrectionSuggestionService {
    private static final int MAX_PROMPT_EVENTS = 24;
    private static final int MAX_PROMPT_RULES = 8;
    private static final int MAX_PROMPT_STEPS = 20;
    private static final int MAX_STEP_CANDIDATES = 8;
    private static final long LLM_TIMEOUT_SECONDS = 20L;
    private static final Pattern GENERIC_CONFIRM_STEP_PATTERN = Pattern.compile(
            "^点击“(确定|确认|提交|保存|完成|下一步|下一页|上一步|返回)”.*$");
    private static final Pattern TEMPORAL_VALUE_PATTERN = Pattern.compile(
            ".*(选择时间|\\d{8}\\s+\\d{1,2}:\\d{2}|\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}.*\\d{1,2}:\\d{2}|\\d{1,2}:\\d{2}).*");
    private static final Pattern DYNAMIC_MARKER_PATTERN = Pattern.compile("\\[(.+?)]|\\{(.+?)}|\\*\\*(.+?)\\*\\*");
    private static final Pattern TEMPLATE_PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([A-Z]+(?:_\\d+)?)}}");
    private static final Pattern DATETIME_PATTERN = Pattern.compile("(\\d{8}\\s+\\d{1,2}:\\d{2}|\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}(?:日)?\\s*\\d{1,2}:\\d{2})");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{8}|\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}(?:日)?)");
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2})");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("((?:1\\d{10})|(?:\\d[*xX]{6,}\\d{2,4})|(?:1\\*{3,}\\d{2,4}))");
    private static final Pattern CODE_PATTERN = Pattern.compile("([A-Za-z0-9]{4,8})");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private static final String CORRECTION_SYSTEM_PROMPT = """
            你是资深测试分析师。请基于以下结构化轨迹事件，识别低置信语义并输出修正建议候选。

            你只处理以下 5 类问题：
            1. OBJECT_LABEL — 列表行对象识别异常（如数字串、无意义文本、被截断的内容）
            2. CHECKBOX_SEMANTICS — checkbox / radio / switch 的语义不稳定（如"已勾选"没有业务含义）
            3. DIALOG_ACTION — 弹窗确认链路中的按钮语义（如"关闭"/"确定"缺乏业务上下文）
            4. INPUT_FINAL_VALUE — 输入法中间态污染最终输入值（如拼音片段、未完成的输入）
            5. BUSINESS_ACTION_MAPPING — 低置信业务动作映射（如元素文本是"test"/"wafer"等无业务含义的词）

            输出格式：JSON 数组，每个元素包含：
            {
              "correctionType": "OBJECT_LABEL|CHECKBOX_SEMANTICS|DIALOG_ACTION|INPUT_FINAL_VALUE|BUSINESS_ACTION_MAPPING",
              "sourceText": "原始文本",
              "candidateValue": "候选修正值",
              "candidateReason": "为什么是低置信以及候选值的依据",
              "confidenceLabel": "HIGH|MEDIUM|LOW",
              "traceEventId": 123
            }

            规则：
            - 只输出真实存在问题的事件，不要虚构。
            - 优先暴露最影响摘要质量的低置信内容，控制数量（每类最多 3 条）。
            - confidenceLabel 判断标准：
              - HIGH：有明确上下文支持，如相邻文本、页面标题、元素 role 辅助确认
              - MEDIUM：有间接线索支持，但存在其他可能
              - LOW：几乎无法从当前上下文确认，需要用户判断
            - 如果某类问题不存在，该类不输出。
            - 只输出 JSON 数组，不要 markdown 或额外解释。
            """;

    // 输入法中间态正则：拼音片段、带单引号的拼音组合
    private static final Pattern INPUT_COMPOSITION_PATTERN = Pattern.compile("[a-z]+'[a-z]+", Pattern.CASE_INSENSITIVE);
    // 纯数字串（可能的对象识别异常）
    private static final Pattern PURE_NUMBER_PATTERN = Pattern.compile("^\\d+$");
    // 无意义短文本（可能是对象识别异常）
    private static final Pattern MEANINGLESS_SHORT_PATTERN = Pattern.compile("^(test|wafer|demo|temp|tmp|xxx|abc)$", Pattern.CASE_INSENSITIVE);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final TraceDataService traceDataService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final List<TraceRulePack> traceRulePacks;
    private final ProjectSemanticContextService semanticContextService;

    public TraceCorrectionSuggestionService(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                            TimeProvider timeProvider, TraceDataService traceDataService,
                                            LlmGateway llmGateway,
                                            ProjectSemanticContextService semanticContextService,
                                            List<TraceRulePack> traceRulePacks) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.traceDataService = traceDataService;
        this.llmGateway = llmGateway;
        this.semanticContextService = semanticContextService;
        this.traceRulePacks = traceRulePacks;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 基于轨迹范围生成修正建议候选。
     * 默认状态 CANDIDATE，必须记录 LLM 快照引用。
     */
    @Transactional
    public List<TraceCorrectionCandidateRecord> generateSuggestions(
            Long traceGroupId, String scope,
            Long traceSessionId, Long issueClipId,
            Long modelConfigId, CurrentUser user) {

        TraceDataService.TraceGroupDetail detail = traceDataService.detail(traceGroupId, user);
        List<BrowserTraceEventRecord> events = filterEvents(detail, traceSessionId, issueClipId);
        List<TraceStepNormalizer.CleanTraceStep> cleanSteps =
                new TraceStepNormalizer(traceRulePacks).normalize(detail.group().projectId(), events, Map.of());

        // 1. 先用规则提取候选特征
        List<RuleCandidate> ruleCandidates = extractRuleCandidates(events);
        List<StepLevelCandidate> stepLevelCandidates = extractStepLevelCandidates(cleanSteps, events);

        // 2. 构建 LLM prompt
        String prompt = buildCorrectionPrompt(
                cleanSteps,
                selectEventsForPrompt(events, ruleCandidates),
                ruleCandidates,
                stepLevelCandidates);

        // 3. 调用 LLM；超时或失败时退化为规则+步骤级建议，避免前端直接 504
        LlmInvocationResponse resp = invokeLlmForCorrectionsWithTimeout(
                modelConfigId, detail.group().projectId(), traceGroupId, prompt, user);

        // 4. 解析 LLM 输出
        List<LlmCorrectionItem> llmItems = resp != null ? parseLlmCorrections(resp.content()) : List.of();

        // 5. 合并规则结果和 LLM 结果（LLM 优先，规则补充）
        List<CorrectionItem> merged = mergeCandidates(ruleCandidates, llmItems, events);

        // 6. 限制每类数量，避免用户负担过重
        List<CorrectionItem> limited = limitPerType(merged, 5);
        List<StepLevelCandidate> limitedStepCandidates = limitStepCandidates(stepLevelCandidates, MAX_STEP_CANDIDATES);

        // 7. 批量写入数据库
        LocalDateTime now = timeProvider.now();
        Long projectId = detail.group().projectId();
        Long promptSnapshotId = resp != null ? resp.promptSnapshotId() : null;
        Long contextManifestId = resp != null ? resp.contextManifestId() : null;
        Long invocationLogId = resp != null ? resp.invocationLogId() : null;

        List<TraceCorrectionCandidateRecord> results = new ArrayList<>();
        for (CorrectionItem item : limited) {
            results.add(insertFieldCandidate(
                    projectId, traceGroupId, traceSessionId, issueClipId,
                    item.traceEventId(), item.correctionType(), item.sourceText(), item.candidateValue(),
                    item.candidateReason(), item.confidenceLabel(), prompt, promptSnapshotId,
                    contextManifestId, invocationLogId, user.id(), now));
        }

        for (StepLevelCandidate item : limitedStepCandidates) {
            results.add(insertStepCandidate(
                    projectId, traceGroupId, traceSessionId, issueClipId,
                    item.stepNo(), toStepCorrectionType(item.operationType()), item.originalStepText(),
                    item.candidateStepText(), item.operationType(), item.relatedStepNo(), item.reason(),
                    "HIGH", prompt, promptSnapshotId, contextManifestId, invocationLogId, user.id(), now));
        }

        return results;
    }

    /**
     * 按 group 列出所有修正建议。
     */
    public List<TraceCorrectionCandidateRecord> listSuggestions(Long traceGroupId, CurrentUser user) {
        return jdbc.sql("""
                        select * from trace_correction_candidate
                        where trace_group_id = :groupId
                        order by
                            field(status, 'CANDIDATE', 'CONFIRMED', 'REJECTED'),
                            field(confidence_label, 'HIGH', 'MEDIUM', 'LOW'),
                            id desc
                        """)
                .param("groupId", traceGroupId)
                .query(this::mapRecord).list();
    }

    /**
     * 获取指定范围内的已确认修正建议，供摘要生成消费。
     */
    public List<TraceCorrectionCandidateRecord> getConfirmedForSummary(
            Long traceGroupId, Long traceSessionId, Long issueClipId) {
        StringBuilder sql = new StringBuilder("""
                select * from trace_correction_candidate
                where trace_group_id = :groupId and status = 'CONFIRMED'
                """);
        if (traceSessionId != null) {
            sql.append(" and trace_session_id = :sessionId");
        }
        if (issueClipId != null) {
            sql.append(" and issue_clip_id = :clipId");
        }
        sql.append(" order by id asc");

        var query = jdbc.sql(sql.toString()).param("groupId", traceGroupId);
        if (traceSessionId != null) {
            query = query.param("sessionId", traceSessionId);
        }
        if (issueClipId != null) {
            query = query.param("clipId", issueClipId);
        }
        return query.query(this::mapRecord).list();
    }

    /**
     * 确认建议。状态切为 CONFIRMED，互斥清理驳回字段。
     * <p>
     * 如果不传 confirmedValue，则按 candidate_value 直接确认；
     * 如果传入 confirmedValue，则视为"手动改写后确认"；
     * 最终采用值统一写入 confirmed_value。
     */
    @Transactional
    public TraceCorrectionCandidateRecord confirmSuggestion(
            Long id,
            String confirmedValue,
            String confirmedStepText,
            CurrentUser user) {
        TraceCorrectionCandidateRecord existing = getById(id);
        if (existing == null) {
            throw new BusinessException("修正建议不存在");
        }
        if ("CONFIRMED".equals(existing.status())) {
            return existing;
        }
        boolean stepScoped = "STEP".equals(existing.correctionScope()) || existing.stepNo() != null;
        String operationType = existing.operationType() != null ? existing.operationType() : inferOperationType(existing);
        String finalValue = (confirmedValue != null && !confirmedValue.isBlank())
                ? confirmedValue.trim()
                : existing.candidateValue();
        String finalStepText = null;
        if (stepScoped && !"DROP".equals(operationType)) {
            finalStepText = (confirmedStepText != null && !confirmedStepText.isBlank())
                    ? confirmedStepText.trim()
                    : (existing.candidateStepText() != null && !existing.candidateStepText().isBlank()
                    ? existing.candidateStepText().trim()
                    : finalValue);
            finalValue = finalStepText;
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update trace_correction_candidate set
                    status = 'CONFIRMED',
                    confirmed_value = ?,
                    confirmed_step_text = ?,
                    confirmed_by = ?, confirmed_at = ?,
                    rejected_by = null, rejected_at = null, rejected_reason = null,
                    updated_at = ?
                where id = ?
                """, finalValue, finalStepText, user.id(), now, now, id);
        learnPatternFromConfirmedSuggestions(existing.traceGroupId(), user);
        return getById(id);
    }

    @Transactional
    public TraceCorrectionCandidateRecord saveManualStepCorrection(
            Long traceGroupId,
            Long traceSessionId,
            Long issueClipId,
            Integer stepNo,
            String sourceText,
            String operationType,
            String confirmedStepText,
            Integer relatedStepNo,
            CurrentUser user) {
        if (stepNo == null || stepNo <= 0) {
            throw new BusinessException("步骤编号不能为空");
        }
        if (sourceText == null || sourceText.isBlank()) {
            throw new BusinessException("原步骤内容不能为空");
        }
        String normalizedOperation = operationType == null ? "REWRITE" : operationType.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("REWRITE", "DROP", "MERGE").contains(normalizedOperation)) {
            throw new BusinessException("步骤修正操作类型不支持");
        }
        StepTemplateParse templateParse = normalizedOperation.equals("DROP")
                ? StepTemplateParse.empty()
                : parseTemplateStepText(confirmedStepText == null ? "" : confirmedStepText.trim());
        String normalizedStepText = normalizedOperation.equals("DROP") ? null : templateParse.displayText();
        if (!normalizedOperation.equals("DROP") && normalizedStepText.isBlank()) {
            throw new BusinessException("修正后的步骤内容不能为空");
        }
        String normalizedCandidateValue = manualStepCandidateValue(sourceText.trim(), normalizedOperation, normalizedStepText);
        String normalizedCandidateStepText = manualStepCandidateStepText(normalizedOperation, normalizedStepText);

        TraceDataService.TraceGroupDetail detail = traceDataService.detail(traceGroupId, user);
        LocalDateTime now = timeProvider.now();
        String rawContextSnapshot = buildManualStepEditSnapshot(
                sourceText.trim(),
                normalizedStepText,
                templateParse.hasTemplateMarkers() ? templateParse.templateText() : null);

        Long id = insertStepCandidateRow(
                detail.group().projectId(),
                traceGroupId,
                traceSessionId,
                issueClipId,
                null,
                toStepCorrectionType(normalizedOperation),
                sourceText.trim(),
                normalizedCandidateValue,
                stepNo,
                "STEP",
                normalizedOperation,
                normalizedCandidateStepText,
                normalizedStepText,
                relatedStepNo,
                "手动编辑清洗后步骤",
                "HIGH",
                rawContextSnapshot,
                null,
                null,
                null,
                user.id(),
                user.id(),
                now,
                now,
                "CONFIRMED");
        learnPatternFromConfirmedSuggestions(traceGroupId, user);
        return getById(id);
    }

    String manualStepCandidateValue(String sourceText, String operationType, String normalizedStepText) {
        if ("DROP".equals(operationType)) {
            return sourceText;
        }
        return normalizedStepText;
    }

    String manualStepCandidateStepText(String operationType, String normalizedStepText) {
        if ("DROP".equals(operationType)) {
            return null;
        }
        return normalizedStepText;
    }

    /**
     * 驳回建议。必须填写原因。
     */
    @Transactional
    public TraceCorrectionCandidateRecord rejectSuggestion(Long id, String reason, CurrentUser user) {
        TraceCorrectionCandidateRecord existing = getById(id);
        if (existing == null) {
            throw new BusinessException("修正建议不存在");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("驳回原因不能为空");
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update trace_correction_candidate set
                    status = 'REJECTED',
                    rejected_by = ?, rejected_at = ?, rejected_reason = ?,
                    confirmed_by = null, confirmed_at = null, confirmed_value = null,
                    updated_at = ?
                where id = ?
                """, user.id(), now, reason, now, id);
        return getById(id);
    }

    /**
     * 将已确认建议格式化为摘要 prompt 的"已确认修正"块。
     * <p>
     * 优先使用 confirmed_value（用户确认或改写后的最终值），fallback 到 candidate_value。
     */
    public String formatConfirmedForPrompt(List<TraceCorrectionCandidateRecord> confirmed) {
        if (confirmed == null || confirmed.isEmpty()) {
            return "无已确认修正建议。";
        }
        StringBuilder sb = new StringBuilder("以下修正建议已获用户确认，生成摘要时请优先采用：\n\n");
        Map<String, List<TraceCorrectionCandidateRecord>> byType = confirmed.stream()
                .collect(Collectors.groupingBy(TraceCorrectionCandidateRecord::correctionType));
        for (Map.Entry<String, List<TraceCorrectionCandidateRecord>> entry : byType.entrySet()) {
            sb.append("【").append(typeLabel(entry.getKey())).append("】\n");
            for (TraceCorrectionCandidateRecord r : entry.getValue()) {
                String adopted = effectiveValue(r);
                sb.append("- 原文：").append(r.sourceText()).append("\n");
                sb.append("  修正为：").append(adopted).append("\n");
                if (r.candidateReason() != null && !r.candidateReason().isBlank()) {
                    sb.append("  依据：").append(r.candidateReason()).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取修正建议的最终采用值：优先 confirmed_value，否则 fallback 到 candidate_value。
     */
    public String effectiveValue(TraceCorrectionCandidateRecord r) {
        if (r == null) return "";
        if (r.confirmedValue() != null && !r.confirmedValue().isBlank()) {
            return r.confirmedValue();
        }
        return r.candidateValue() != null ? r.candidateValue() : "";
    }

    // ------------------------------------------------------------------
    // 规则分析 — 从轨迹事件提取候选特征
    // ------------------------------------------------------------------

    private List<RuleCandidate> extractRuleCandidates(List<BrowserTraceEventRecord> events) {
        List<RuleCandidate> candidates = new ArrayList<>();
        Map<String, List<BrowserTraceEventRecord>> inputSequences = new LinkedHashMap<>();

        for (BrowserTraceEventRecord e : events) {
            // OBJECT_LABEL: object_label 异常
            if (e.objectLabel() != null && !e.objectLabel().isBlank()) {
                if (isSuspiciousObjectLabel(e.objectLabel())) {
                    candidates.add(new RuleCandidate(
                            "OBJECT_LABEL", e.id(), e.objectLabel(),
                            suggestObjectLabel(e), "对象识别疑似被污染或截断"));
                }
            }

            // CHECKBOX_SEMANTICS: checkbox / radio 相关
            if (isCheckboxEvent(e)) {
                String semantics = inferCheckboxSemantics(e);
                if (semantics != null) {
                    candidates.add(new RuleCandidate(
                            "CHECKBOX_SEMANTICS", e.id(),
                            e.elementText() != null ? e.elementText() : (e.valueSummary() != null ? e.valueSummary() : ""),
                            semantics, "checkbox 业务语义需确认"));
                }
            }

            // DIALOG_ACTION: 弹窗中的按钮点击
            if ("CLICK".equals(e.eventType()) && e.dialogTitle() != null && !e.dialogTitle().isBlank()) {
                candidates.add(new RuleCandidate(
                        "DIALOG_ACTION", e.id(), e.elementText() != null ? e.elementText() : "",
                        suggestDialogAction(e), "弹窗按钮需补充业务上下文"));
            }

            // INPUT_FINAL_VALUE: 收集 INPUT/CHANGE 事件用于序列分析
            if (("INPUT".equals(e.eventType()) || "CHANGE".equals(e.eventType()))
                    && e.valueSummary() != null && !e.valueSummary().isBlank()) {
                String key = e.traceSessionId() + "_" + (e.selector() != null ? e.selector() : e.elementText());
                inputSequences.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }

            // BUSINESS_ACTION_MAPPING: 低置信元素文本
            if (e.elementText() != null && isLowConfidenceActionText(e.elementText())) {
                String suggestedAction = suggestBusinessAction(e);
                candidates.add(new RuleCandidate(
                        "BUSINESS_ACTION_MAPPING", e.id(), e.elementText(),
                        suggestedAction,
                        "元素文本缺乏明确业务语义，请确认或改写为正确业务动作"));
            }
        }

        // INPUT_FINAL_VALUE: 分析输入序列，找中间态
        for (List<BrowserTraceEventRecord> seq : inputSequences.values()) {
            if (seq.size() >= 2) {
                BrowserTraceEventRecord last = seq.get(seq.size() - 1);
                BrowserTraceEventRecord first = seq.get(0);
                // 如果首尾不同，且中间有拼音特征，标记为输入法中间态
                if (!Objects.equals(first.valueSummary(), last.valueSummary())) {
                    boolean hasComposition = seq.stream()
                            .anyMatch(e -> INPUT_COMPOSITION_PATTERN.matcher(e.valueSummary()).find());
                    if (hasComposition) {
                        candidates.add(new RuleCandidate(
                                "INPUT_FINAL_VALUE", last.id(), last.valueSummary(),
                                "输入法中间态合并后的最终值：" + last.valueSummary(),
                                "检测到输入法拼音中间态污染"));
                    }
                }
            }
        }

        return candidates;
    }

    private boolean isSuspiciousObjectLabel(String label) {
        if (label.length() > 30 && PURE_NUMBER_PATTERN.matcher(label).matches()) return true;
        if (label.length() < 3 && MEANINGLESS_SHORT_PATTERN.matcher(label).matches()) return true;
        // 混合数字和字母的乱码
        if (label.matches(".*\\d{5,}.*")) return true;
        // 看起来像测试数据的随机数字串
        if (label.matches("^\\d+\\.\\d+.*")) return true;
        return false;
    }

    private String suggestObjectLabel(BrowserTraceEventRecord e) {
        String fromPack = firstNonBlankPackSuggestion(pack -> pack.suggestObjectLabel(e));
        if (fromPack != null) {
            return fromPack;
        }
        // 尝试从 page_title 或相邻事件推断
        if (e.sectionTitle() != null && !e.sectionTitle().isBlank()) {
            return e.sectionTitle() + " 行对象";
        }
        return "列表行对象（需人工确认）";
    }

    private boolean isCheckboxEvent(BrowserTraceEventRecord e) {
        String role = e.elementRole();
        if (role != null) {
            String r = role.toLowerCase();
            return r.contains("checkbox") || r.contains("radio") || r.contains("switch");
        }
        String text = e.elementText();
        if (text != null) {
            String t = text.toLowerCase();
            return t.contains("勾选") || t.contains("选中") || t.contains("checkbox");
        }
        return false;
    }

    private String inferCheckboxSemantics(BrowserTraceEventRecord e) {
        String fromPack = firstNonBlankPackSuggestion(pack -> pack.suggestCheckboxSemantics(e));
        if (fromPack != null) {
            return fromPack;
        }
        String text = e.elementText();
        if (text == null || text.isBlank()) return null;
        // 简单推断：如果有 "发送"、"通知" 等词，推测为选项
        if (text.contains("发送")) return "选择\"发送通知\"选项";
        return null;
    }

    String inferCheckboxSemanticsForTest(BrowserTraceEventRecord e) {
        return inferCheckboxSemantics(e);
    }

    private String suggestDialogAction(BrowserTraceEventRecord e) {
        String fromPack = firstNonBlankPackSuggestion(pack -> pack.suggestDialogAction(e));
        if (fromPack != null) {
            return fromPack;
        }
        String text = e.elementText();
        String dialog = e.dialogTitle();
        if (text == null) text = "";
        if (dialog == null) dialog = "";

        if (text.contains("关闭")) return "关闭弹窗";
        if (text.contains("确定") || text.contains("确认")) {
            if (dialog.contains("删除")) return "确认删除";
            if (dialog.contains("保存")) return "确认保存";
            return "确认当前操作";
        }
        return text + "（需结合弹窗标题确认业务含义）";
    }

    private boolean isLowConfidenceActionText(String text) {
        String lower = text.toLowerCase().trim();
        return lower.matches("^(test|wafer|demo|temp|tmp|abc|xyz|qwe|asd).*$")
                || lower.matches("^\\d+$");
    }

    private String suggestBusinessAction(BrowserTraceEventRecord e) {
        String fromPack = firstNonBlankPackSuggestion(pack -> pack.suggestBusinessAction(e));
        if (fromPack != null) {
            return fromPack;
        }
        String text = e.elementText();
        // 列表行对象
        if (text != null && text.contains(" · ")) {
            return "列表行操作（需确认对象名称）";
        }
        // 纯数字串
        if (text != null && text.matches("^\\d+$")) {
            return "数字对象（需确认业务含义）";
        }
        return "";
    }

    private String firstNonBlankPackSuggestion(java.util.function.Function<TraceRulePack, String> extractor) {
        if (traceRulePacks == null || traceRulePacks.isEmpty()) {
            return null;
        }
        for (TraceRulePack pack : traceRulePacks) {
            String value = extractor.apply(pack);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Prompt 构建与 LLM 调用
    // ------------------------------------------------------------------

    private String buildCorrectionPrompt(List<TraceStepNormalizer.CleanTraceStep> cleanSteps,
                                         List<BrowserTraceEventRecord> events,
                                         List<RuleCandidate> ruleCandidates,
                                         List<StepLevelCandidate> stepLevelCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("【清洗后操作步骤】\n");
        if (cleanSteps == null || cleanSteps.isEmpty()) {
            sb.append("无可用步骤\n");
        } else {
            cleanSteps.stream()
                    .limit(MAX_PROMPT_STEPS)
                    .forEach(step -> sb.append(String.format("[%d] %s | page=%s | action=%s | desc=%s\n",
                            step.stepNo(),
                            safe(step.actor(), "默认身份"),
                            safe(step.pageName(), step.pageUrl()),
                            safe(step.actionType(), "-"),
                            safe(step.description(), "-"))));
        }

        sb.append("\n【原始事件摘录】\n");
        for (BrowserTraceEventRecord e : events) {
            sb.append(String.format("[%d] %s | page=%s | text=%s | role=%s | value=%s | locator=%s | dialog=%s | object=%s\n",
                    e.id(), e.eventType(),
                    safe(e.pageTitle(), e.pageUrl()),
                    safe(e.elementText(), "-"),
                    safe(e.elementRole(), "-"),
                    safe(e.valueSummary(), "-"),
                    safe(e.normalizedLocator(), "-"),
                    safe(e.dialogTitle(), "-"),
                    safe(e.objectLabel(), "-")));
        }

        if (!ruleCandidates.isEmpty()) {
            sb.append("\n【规则预提取候选】\n");
            ruleCandidates.stream().limit(MAX_PROMPT_RULES).forEach(rc -> {
                sb.append(String.format("- type=%s | eventId=%d | source=%s | candidate=%s | reason=%s\n",
                        rc.type(), rc.eventId(), rc.sourceText(), rc.candidateValue(), rc.reason()));
            });
            sb.append("\n请基于以上规则候选和原始事件，确认、补充或修正候选建议。\n");
        }
        if (stepLevelCandidates != null && !stepLevelCandidates.isEmpty()) {
            sb.append("\n【步骤级候选】\n");
            stepLevelCandidates.stream().limit(MAX_PROMPT_RULES).forEach(item -> {
                sb.append(String.format("- step=%d | op=%s | original=%s | candidate=%s | reason=%s\n",
                        item.stepNo(),
                        item.operationType(),
                        safe(item.originalStepText(), "-"),
                        safe(item.candidateStepText(), "-"),
                        safe(item.reason(), "-")));
            });
        }

        return sb.toString();
    }

    private LlmInvocationResponse invokeLlmForCorrectionsWithTimeout(Long modelConfigId, Long projectId, Long taskId,
                                                                     String userPrompt, CurrentUser user) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> invokeLlmForCorrections(modelConfigId, projectId, taskId, userPrompt, user))
                    .get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeoutException) {
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private LlmInvocationResponse invokeLlmForCorrections(Long modelConfigId, Long projectId, Long taskId,
                                                          String userPrompt, CurrentUser user) {
        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, taskId,
                "TRACE_CORRECTION", LlmStage.TRACE_CORRECTION,
                modelConfigId, null, null, java.util.Map.of(),
                CORRECTION_SYSTEM_PROMPT, userPrompt, taskId));
        if (resp.status() != LlmInvocationStatus.OK) {
            throw new BusinessException(
                    "模型调用失败：" + (resp.errorMessage() == null ? resp.status().name() : resp.errorMessage()));
        }
        return resp;
    }

    // ------------------------------------------------------------------
    // LLM 输出解析
    // ------------------------------------------------------------------

    private List<LlmCorrectionItem> parseLlmCorrections(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String jsonText = extractJson(raw);
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(jsonText, new TypeReference<>() {});
            List<LlmCorrectionItem> items = new ArrayList<>();
            for (Map<String, Object> m : parsed) {
                String type = str(m.get("correctionType"), "");
                String source = str(m.get("sourceText"), "");
                String candidate = str(m.get("candidateValue"), "");
                String reason = str(m.get("candidateReason"), "");
                String confidence = str(m.get("confidenceLabel"), "MEDIUM");
                Long eventId = parseLong(m.get("traceEventId"));
                if (!type.isBlank() && !source.isBlank()) {
                    items.add(new LlmCorrectionItem(type, source, candidate, reason, confidence, eventId));
                }
            }
            return items;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 候选合并与限流
    // ------------------------------------------------------------------

    private List<CorrectionItem> mergeCandidates(List<RuleCandidate> rules, List<LlmCorrectionItem> llmItems,
                                                  List<BrowserTraceEventRecord> events) {
        Map<String, CorrectionItem> merged = new LinkedHashMap<>();

        // LLM 结果优先
        for (LlmCorrectionItem li : llmItems) {
            String key = li.type() + "_" + li.sourceText();
            merged.put(key, new CorrectionItem(li.type(), li.sourceText(), li.candidateValue(),
                    li.candidateReason(), li.confidenceLabel(), li.traceEventId()));
        }

        // 规则结果补充（去重）
        for (RuleCandidate rc : rules) {
            String key = rc.type() + "_" + rc.sourceText();
            if (!merged.containsKey(key)) {
                Long eventId = rc.eventId();
                String confidence = rc.type().equals("INPUT_FINAL_VALUE") ? "HIGH" : "MEDIUM";
                merged.put(key, new CorrectionItem(rc.type(), rc.sourceText(), rc.candidateValue(),
                        rc.reason(), confidence, eventId));
            }
        }

        return new ArrayList<>(merged.values());
    }

    private List<CorrectionItem> limitPerType(List<CorrectionItem> items, int maxPerType) {
        Map<String, List<CorrectionItem>> byType = items.stream()
                .collect(Collectors.groupingBy(CorrectionItem::correctionType));
        List<CorrectionItem> result = new ArrayList<>();
        for (List<CorrectionItem> list : byType.values()) {
            // 按置信度排序，高置信优先
            list.sort(Comparator.comparingInt(item -> confidenceOrder(item.confidenceLabel())));
            result.addAll(list.subList(0, Math.min(list.size(), maxPerType)));
        }
        return result;
    }

    private List<StepLevelCandidate> limitStepCandidates(List<StepLevelCandidate> items, int maxItems) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .sorted(Comparator
                        .comparingInt((StepLevelCandidate item) -> stepOperationOrder(item.operationType()))
                        .thenComparingInt(StepLevelCandidate::stepNo))
                .limit(maxItems)
                .toList();
    }

    private int confidenceOrder(String label) {
        return switch (label) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private int stepOperationOrder(String operationType) {
        return switch (operationType) {
            case "DROP" -> 0;
            case "REWRITE" -> 1;
            case "MERGE" -> 2;
            default -> 3;
        };
    }

    // ------------------------------------------------------------------
    // 事件过滤
    // ------------------------------------------------------------------

    private List<BrowserTraceEventRecord> filterEvents(TraceDataService.TraceGroupDetail detail,
                                                        Long traceSessionId, Long issueClipId) {
        List<BrowserTraceEventRecord> events = detail.events();
        if (traceSessionId != null) {
            events = events.stream()
                    .filter(e -> Objects.equals(e.traceSessionId(), traceSessionId))
                    .toList();
        }
        if (issueClipId != null) {
            BrowserIssueClipRecord clip = detail.issueClips().stream()
                    .filter(c -> Objects.equals(c.id(), issueClipId))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("问题片段不存在"));
            events = events.stream()
                    .filter(e -> e.relativeMs() >= clip.clipStartRelativeMs()
                            && e.relativeMs() <= clip.clipEndRelativeMs())
                    .toList();
        }
        return events;
    }

    private List<BrowserTraceEventRecord> selectEventsForPrompt(List<BrowserTraceEventRecord> events,
                                                                List<RuleCandidate> ruleCandidates) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Set<Long> highlightedEventIds = ruleCandidates.stream()
                .map(RuleCandidate::eventId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (!highlightedEventIds.isEmpty()) {
            List<BrowserTraceEventRecord> focused = events.stream()
                    .filter(event -> highlightedEventIds.contains(event.id()))
                    .limit(MAX_PROMPT_EVENTS)
                    .toList();
            if (!focused.isEmpty()) {
                return focused;
            }
        }
        if (events.size() <= MAX_PROMPT_EVENTS) {
            return events;
        }
        return events.subList(Math.max(0, events.size() - MAX_PROMPT_EVENTS), events.size());
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private TraceCorrectionCandidateRecord getById(Long id) {
        var results = jdbc.sql("select * from trace_correction_candidate where id = :id")
                .param("id", id).query(this::mapRecord).list();
        return results.isEmpty() ? null : results.get(0);
    }

    private TraceCorrectionCandidateRecord insertFieldCandidate(
            Long projectId, Long traceGroupId, Long traceSessionId, Long issueClipId, Long traceEventId,
            String correctionType, String sourceText, String candidateValue, String candidateReason,
            String confidenceLabel, String rawContextSnapshot, Long promptSnapshotId,
            Long contextManifestId, Long invocationLogId, Long createdBy, LocalDateTime now) {
        Long id = insertFieldCandidateRow(
                projectId, traceGroupId, traceSessionId, issueClipId, traceEventId,
                correctionType, sourceText, candidateValue, candidateReason, confidenceLabel,
                rawContextSnapshot, promptSnapshotId, contextManifestId, invocationLogId,
                createdBy, now, "CANDIDATE");
        return getById(id);
    }

    private Long insertFieldCandidateRow(
            Long projectId, Long traceGroupId, Long traceSessionId, Long issueClipId, Long traceEventId,
            String correctionType, String sourceText, String candidateValue, String candidateReason,
            String confidenceLabel, String rawContextSnapshot, Long promptSnapshotId,
            Long contextManifestId, Long invocationLogId, Long createdBy, LocalDateTime now,
            String status) {
        jdbcTemplate.update("""
                insert into trace_correction_candidate(
                    project_id, trace_group_id, trace_session_id, issue_clip_id, trace_event_id,
                    correction_type, source_text, candidate_value, candidate_reason, confidence_label,
                    status, raw_context_snapshot, prompt_snapshot_id, context_manifest_id, llm_invocation_log_id,
                    created_by, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                projectId, traceGroupId, traceSessionId, issueClipId, traceEventId,
                correctionType, sourceText, candidateValue, candidateReason, confidenceLabel,
                status, rawContextSnapshot, promptSnapshotId, contextManifestId, invocationLogId,
                createdBy, now, now);
        return jdbc.sql("select last_insert_id()").query(Long.class).single();
    }

    private TraceCorrectionCandidateRecord insertStepCandidate(
            Long projectId, Long traceGroupId, Long traceSessionId, Long issueClipId,
            Integer stepNo, String correctionType, String sourceText, String candidateStepText,
            String operationType, Integer relatedStepNo, String candidateReason, String confidenceLabel,
            String rawContextSnapshot, Long promptSnapshotId, Long contextManifestId, Long invocationLogId,
            Long createdBy, LocalDateTime now) {
        Long id = insertStepCandidateRow(
                projectId, traceGroupId, traceSessionId, issueClipId, null,
                correctionType, sourceText, candidateStepText, stepNo, "STEP", operationType,
                candidateStepText, null, relatedStepNo, candidateReason, confidenceLabel,
                rawContextSnapshot, promptSnapshotId, contextManifestId, invocationLogId,
                createdBy, null, null, now, "CANDIDATE");
        return getById(id);
    }

    private Long insertStepCandidateRow(
            Long projectId, Long traceGroupId, Long traceSessionId, Long issueClipId, Long traceEventId,
            String correctionType, String sourceText, String candidateValue, Integer stepNo,
            String correctionScope, String operationType, String candidateStepText, String confirmedStepText,
            Integer relatedStepNo, String candidateReason, String confidenceLabel, String rawContextSnapshot,
            Long promptSnapshotId, Long contextManifestId, Long invocationLogId, Long createdBy,
            Long confirmedBy, LocalDateTime confirmedAt, LocalDateTime now, String status) {
        jdbcTemplate.update("""
                insert into trace_correction_candidate(
                    project_id, trace_group_id, trace_session_id, issue_clip_id, trace_event_id,
                    correction_type, source_text, candidate_value, candidate_reason, confidence_label,
                    step_no, correction_scope, operation_type, candidate_step_text, confirmed_step_text, related_step_no,
                    status, raw_context_snapshot, prompt_snapshot_id, context_manifest_id, llm_invocation_log_id,
                    created_by, confirmed_by, confirmed_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                projectId, traceGroupId, traceSessionId, issueClipId, traceEventId,
                correctionType, sourceText, candidateValue, candidateReason, confidenceLabel,
                stepNo, correctionScope, operationType, candidateStepText, confirmedStepText, relatedStepNo,
                status, rawContextSnapshot, promptSnapshotId, contextManifestId, invocationLogId,
                createdBy, confirmedBy, confirmedAt, now, now);
        return jdbc.sql("select last_insert_id()").query(Long.class).single();
    }

    private String toStepCorrectionType(String operationType) {
        return switch (operationType) {
            case "DROP" -> "STEP_NOISE_DECISION";
            case "MERGE" -> "STEP_MERGE_SUGGESTION";
            default -> "STEP_TEXT_OVERRIDE";
        };
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "OBJECT_LABEL" -> "对象识别建议";
            case "CHECKBOX_SEMANTICS" -> "Checkbox / Selection 语义建议";
            case "DIALOG_ACTION" -> "弹窗确认链路建议";
            case "INPUT_FINAL_VALUE" -> "输入最终值建议";
            case "BUSINESS_ACTION_MAPPING" -> "低置信业务动作映射";
            default -> type;
        };
    }

    private String extractJson(String raw) {
        if (raw == null) return "[]";
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

    private String str(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String safe(String first, String fallback) {
        if (first != null && !first.isBlank()) return first;
        return fallback == null ? "" : fallback;
    }

    // ------------------------------------------------------------------
    // 即时应用：将已确认修正应用到清洗后步骤
    // ------------------------------------------------------------------

    /**
     * 将已确认修正建议应用到清洗后步骤描述中。
     * <p>
     * 只作用于当前轨迹上下文，不影响原始事件记录或其他轨迹组。
     *
     * @param steps       原始清洗后步骤
     * @param corrections 该范围内的已确认修正建议
     * @return 应用修正后的步骤列表
     */
    public List<TraceStepNormalizer.CleanTraceStep> applyConfirmedToCleanSteps(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            List<TraceCorrectionCandidateRecord> corrections) {
        if (steps == null || steps.isEmpty() || corrections == null || corrections.isEmpty()) {
            return steps;
        }
        List<TraceStepNormalizer.CleanTraceStep> result = new ArrayList<>();
        for (TraceStepNormalizer.CleanTraceStep step : steps) {
            String desc = step.description();
            String modified = desc;
            for (TraceCorrectionCandidateRecord c : corrections) {
                if (!"CONFIRMED".equals(c.status())) continue;
                if (isStepScopedCorrection(c)) continue;
                String adopted = effectiveValue(c);
                if (adopted == null || adopted.isBlank()) continue;
                modified = applyCorrectionToDescription(modified, c.correctionType(), c.sourceText(), adopted);
            }
            if (!Objects.equals(desc, modified)) {
                result.add(new TraceStepNormalizer.CleanTraceStep(
                        step.stepNo(), step.actor(), step.actionType(), modified,
                        step.pageName(), step.pageUrl(), step.relativeMs()));
            } else {
                result.add(step);
            }
        }
        return result;
    }

    private String applyCorrectionToDescription(String desc, String correctionType, String sourceText, String confirmedValue) {
        if (desc == null || sourceText == null || confirmedValue == null) return desc;
        switch (correctionType) {
            case "OBJECT_LABEL" -> {
                // 替换对象标签文本
                if (desc.contains(sourceText)) {
                    return desc.replace(sourceText, confirmedValue);
                }
            }
            case "CHECKBOX_SEMANTICS" -> {
                // 替换 checkbox 语义表达
                if (desc.contains(sourceText)) {
                    return desc.replace(sourceText, confirmedValue);
                }
            }
            case "DIALOG_ACTION" -> {
                // 替换弹窗动作描述
                if (desc.contains(sourceText)) {
                    return desc.replace(sourceText, confirmedValue);
                }
            }
            case "INPUT_FINAL_VALUE" -> {
                // 替换输入最终值：匹配 "输入XXX" 中的值部分
                if (desc.contains(sourceText)) {
                    return desc.replace(sourceText, confirmedValue);
                }
            }
            case "BUSINESS_ACTION_MAPPING" -> {
                // 替换低置信动作文本
                if (desc.contains(sourceText)) {
                    return desc.replace(sourceText, confirmedValue);
                }
            }
        }
        return desc;
    }

    // ------------------------------------------------------------------
    // 步骤级修正 — 生成、应用
    // ------------------------------------------------------------------

    /**
     * 从清洗后步骤中提取步骤级修正候选。
     *<p>
     * 识别以下三类问题：
     * 1. STEP_TEXT_OVERRIDE — 整条步骤文案表述不准确（如缺少行号、缺少业务上下文）
     * 2. STEP_NOISE_DECISION — 明显的噪音步骤（如输入法中间态、焦点跳动）
     * 3. STEP_MERGE_SUGGESTION — 相邻重复或连续相似步骤可合并
     *
     * @param steps 清洗后步骤
     * @param events 原始轨迹事件（用于上下文推断）
     * @return 步骤级修正候选列表
     */
    public List<StepLevelCandidate> extractStepLevelCandidates(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            List<BrowserTraceEventRecord> events) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<StepLevelCandidate> candidates = new ArrayList<>();
        Map<Integer, BrowserTraceEventRecord> eventByStep = mapEventsToSteps(events, steps);

        for (int i = 0; i < steps.size(); i++) {
            TraceStepNormalizer.CleanTraceStep step = steps.get(i);
            BrowserTraceEventRecord event = eventByStep.get(step.stepNo());

            // STEP_TEXT_OVERRIDE: 检查是否需要补充行号或业务上下文
            String rewritten = suggestStepTextRewrite(step, event);
            if (rewritten != null && !rewritten.equals(step.description())) {
                candidates.add(new StepLevelCandidate(
                        step.stepNo(), "STEP", "REWRITE",
                        step.description(), rewritten,
                        null,
                        "步骤文案可补充业务上下文或行号定位"));
            }

            // STEP_NOISE_DECISION: 识别噪音步骤
            if (isNoiseStep(step, event)) {
                candidates.add(new StepLevelCandidate(
                        step.stepNo(), "STEP", "DROP",
                        step.description(), "",
                        null,
                        "疑似噪音步骤（输入法中间态或焦点跳动）"));
            }
        }

        // STEP_MERGE_SUGGESTION: 检查相邻步骤是否可合并
        for (int i = 0; i < steps.size() - 1; i++) {
            TraceStepNormalizer.CleanTraceStep current = steps.get(i);
            TraceStepNormalizer.CleanTraceStep next = steps.get(i + 1);
            if (canMergeSteps(current, next)) {
                String mergedText = mergeStepTexts(current, next);
                candidates.add(new StepLevelCandidate(
                        current.stepNo(), "STEP", "MERGE",
                        current.description(), mergedText,
                        next.stepNo(),
                        "相邻步骤业务语义重复，建议合并"));
            }
        }

        return candidates;
    }

    /**
     * 将步骤级修正应用到清洗后步骤。
     *<p>
     * 按 step_no 匹配，应用 REWRITE / DROP / MERGE 操作。
     * 只作用于当前轨迹上下文，不影响原始事件或其他轨迹。
     *
     * @param steps       原始清洗后步骤
     * @param corrections 已确认的步骤级修正建议（correctionScope = 'STEP'）
     * @return 应用修正后的步骤列表
     */
    public List<TraceStepNormalizer.CleanTraceStep> applyStepCorrectionsToCleanSteps(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            List<TraceCorrectionCandidateRecord> corrections) {
        if (steps == null || steps.isEmpty() || corrections == null || corrections.isEmpty()) {
            return steps;
        }

        // 过滤出已确认的步骤级修正
        List<TraceCorrectionCandidateRecord> stepCorrections = corrections.stream()
                .filter(c -> "CONFIRMED".equals(c.status()))
                .filter(c -> "STEP".equals(c.correctionScope()) || "STEP_TEXT_OVERRIDE".equals(c.correctionType())
                        || "STEP_NOISE_DECISION".equals(c.correctionType()) || "STEP_MERGE_SUGGESTION".equals(c.correctionType()))
                .sorted(Comparator.comparingInt((TraceCorrectionCandidateRecord c) -> c.stepNo() != null ? c.stepNo() : 0).reversed())
                .toList();

        if (stepCorrections.isEmpty()) {
            return steps;
        }

        List<TraceStepNormalizer.CleanTraceStep> result = new ArrayList<>(steps);
        Set<Integer> droppedStepNos = new HashSet<>();

        for (TraceCorrectionCandidateRecord c : stepCorrections) {
            Integer stepNo = c.stepNo();
            if (stepNo == null) continue;
            String opType = c.operationType();
            if (opType == null) opType = inferOperationType(c);

            switch (opType) {
                case "DROP" -> {
                    droppedStepNos.add(stepNo);
                }
                case "REWRITE" -> {
                    String newText = effectiveStepText(c);
                    for (int i = 0; i < result.size(); i++) {
                        TraceStepNormalizer.CleanTraceStep s = result.get(i);
                        if (s.stepNo() == stepNo) {
                            result.set(i, new TraceStepNormalizer.CleanTraceStep(
                                    s.stepNo(), s.actor(), s.actionType(), newText,
                                    s.pageName(), s.pageUrl(), s.relativeMs()));
                            break;
                        }
                    }
                }
                case "MERGE" -> {
                    Integer relatedStepNo = c.relatedStepNo();
                    if (relatedStepNo == null) continue;
                    String mergedText = effectiveStepText(c);
                    int targetIdx = -1;
                    for (int i = 0; i < result.size(); i++) {
                        if (result.get(i).stepNo() == stepNo) {
                            targetIdx = i;
                            break;
                        }
                    }
                    if (targetIdx >= 0) {
                        TraceStepNormalizer.CleanTraceStep target = result.get(targetIdx);
                        result.set(targetIdx, new TraceStepNormalizer.CleanTraceStep(
                                target.stepNo(), target.actor(), target.actionType(), mergedText,
                                target.pageName(), target.pageUrl(), target.relativeMs()));
                        droppedStepNos.add(relatedStepNo);
                    }
                }
            }
        }

        // 移除被标记为 DROP 的步骤
        if (!droppedStepNos.isEmpty()) {
            result = result.stream()
                    .filter(s -> !droppedStepNos.contains(s.stepNo()))
                    .collect(Collectors.toList());
        }

        return result;
    }

    public List<TraceStepNormalizer.CleanTraceStep> applyLearnedPatternsToCleanSteps(
            Long projectId,
            List<TraceStepNormalizer.CleanTraceStep> steps) {
        return applyLearnedPatternsToCleanSteps(projectId, steps, List.of());
    }

    public List<TraceStepNormalizer.CleanTraceStep> applyLearnedPatternsToCleanSteps(
            Long projectId,
            List<TraceStepNormalizer.CleanTraceStep> steps,
            List<BrowserTraceEventRecord> events) {
        if (projectId == null || steps == null || steps.isEmpty()) {
            return steps;
        }
        List<TraceCorrectionPatternRecord> patterns = jdbc.sql("""
                        select * from trace_correction_pattern
                        where project_id = :projectId
                          and correction_type in ('STEP_TEXT_OVERRIDE', 'STEP_NOISE_DECISION', 'STEP_MERGE_SUGGESTION')
                        order by confirmed_count desc, id desc
                        """)
                .param("projectId", projectId)
                .query(this::mapPatternRecord)
                .list();
        return applyLearnedPatternsToCleanSteps(projectId, steps, patterns, events);
    }

    List<TraceStepNormalizer.CleanTraceStep> applyLearnedPatternsToCleanStepsForTest(
            Long projectId,
            List<TraceStepNormalizer.CleanTraceStep> steps,
            List<TraceCorrectionPatternRecord> patterns) {
        return applyLearnedPatternsToCleanSteps(projectId, steps, patterns, List.of());
    }

    List<TraceStepNormalizer.CleanTraceStep> applyLearnedPatternsToCleanStepsForTest(
            Long projectId,
            List<TraceStepNormalizer.CleanTraceStep> steps,
            List<TraceCorrectionPatternRecord> patterns,
            List<BrowserTraceEventRecord> events) {
        return applyLearnedPatternsToCleanSteps(projectId, steps, patterns, events);
    }

    private List<TraceStepNormalizer.CleanTraceStep> applyLearnedPatternsToCleanSteps(
            Long projectId,
            List<TraceStepNormalizer.CleanTraceStep> steps,
            List<TraceCorrectionPatternRecord> patterns,
            List<BrowserTraceEventRecord> events) {
        if (projectId == null || steps == null || steps.isEmpty()) {
            return steps;
        }
        if (patterns.isEmpty()) {
            return steps;
        }

        List<TraceStepNormalizer.CleanTraceStep> result = new ArrayList<>(steps);
        Set<Integer> dropped = new HashSet<>();
        List<BrowserTraceEventRecord> orderedEvents = events == null ? List.of() : events.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(BrowserTraceEventRecord::relativeMs,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();
        Map<Integer, BrowserTraceEventRecord> eventByStep = mapEventsToSteps(orderedEvents, result);
        Map<Long, Integer> eventIndexById = new HashMap<>();
        for (int i = 0; i < orderedEvents.size(); i++) {
            BrowserTraceEventRecord event = orderedEvents.get(i);
            if (event.id() != null) {
                eventIndexById.put(event.id(), i);
            }
        }

        for (int i = 0; i < result.size(); i++) {
            TraceStepNormalizer.CleanTraceStep step = result.get(i);
            TraceCorrectionPatternRecord matched = patterns.stream()
                    .filter(pattern -> matchesLearnedPattern(pattern, step))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                continue;
            }
            String operationType = matched.operationType() == null ? "REWRITE" : matched.operationType();
            if (shouldIgnoreLearnedPattern(matched, step, operationType)) {
                continue;
            }
            StepRenderContext renderContext = buildStepRenderContext(
                    result, i, eventByStep.get(step.stepNo()), orderedEvents, eventIndexById);
            String renderedText = "REWRITE".equals(operationType) || "MERGE".equals(operationType)
                    ? renderTemplateStepText(matched.toText(), renderContext)
                    : matched.toText();
            if (("REWRITE".equals(operationType) || "MERGE".equals(operationType))
                    && (renderedText == null || renderedText.isBlank())) {
                continue;
            }
            switch (operationType) {
                case "DROP" -> dropped.add(step.stepNo());
                case "REWRITE" -> result.set(i, new TraceStepNormalizer.CleanTraceStep(
                        step.stepNo(), step.actor(), step.actionType(), renderedText,
                        step.pageName(), step.pageUrl(), step.relativeMs()));
                case "MERGE" -> {
                    if (i + 1 < result.size()) {
                        TraceStepNormalizer.CleanTraceStep next = result.get(i + 1);
                        result.set(i, new TraceStepNormalizer.CleanTraceStep(
                                step.stepNo(), step.actor(), step.actionType(), renderedText,
                                step.pageName(), step.pageUrl(), step.relativeMs()));
                        dropped.add(next.stepNo());
                    }
                }
            }
        }

        if (!dropped.isEmpty()) {
            result = result.stream().filter(step -> !dropped.contains(step.stepNo())).toList();
        }
        return result;
    }

    /**
     * 推断操作类型（兼容旧数据没有 operation_type 的情况）。
     */
    private String inferOperationType(TraceCorrectionCandidateRecord c) {
        String type = c.correctionType();
        if (type == null) return "REWRITE";
        return switch (type) {
            case "STEP_TEXT_OVERRIDE" -> "REWRITE";
            case "STEP_NOISE_DECISION" -> "DROP";
            case "STEP_MERGE_SUGGESTION" -> "MERGE";
            default -> "REWRITE";
        };
    }

    /**
     * 获取步骤级修正的最终采用文本。
     */
    private String effectiveStepText(TraceCorrectionCandidateRecord c) {
        if (c.confirmedStepText() != null && !c.confirmedStepText().isBlank()) {
            return c.confirmedStepText();
        }
        if (c.candidateStepText() != null && !c.candidateStepText().isBlank()) {
            return c.candidateStepText();
        }
        return effectiveValue(c);
    }

    private boolean isStepScopedCorrection(TraceCorrectionCandidateRecord c) {
        if (c == null) {
            return false;
        }
        if ("STEP".equals(c.correctionScope()) || c.stepNo() != null) {
            return true;
        }
        return "STEP_TEXT_OVERRIDE".equals(c.correctionType())
                || "STEP_NOISE_DECISION".equals(c.correctionType())
                || "STEP_MERGE_SUGGESTION".equals(c.correctionType());
    }

    private boolean matchesLearnedPattern(TraceCorrectionPatternRecord pattern, TraceStepNormalizer.CleanTraceStep step) {
        if (pattern == null || step == null) {
            return false;
        }
        if (pattern.fromText() == null || !pattern.fromText().equals(step.description())) {
            return false;
        }
        String pageKeyword = pattern.pageTitleKeyword();
        if (pageKeyword != null && !pageKeyword.isBlank()) {
            String pageName = step.pageName() == null ? "" : step.pageName();
            if (!pageName.contains(pageKeyword)) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldIgnoreLearnedPattern(
            TraceCorrectionPatternRecord pattern,
            TraceStepNormalizer.CleanTraceStep step,
            String operationType) {
        if (!"REWRITE".equals(operationType)) {
            return false;
        }
        return isRiskyGenericStepRewrite(pattern.fromText(), pattern.toText())
                || isRiskyGenericStepRewrite(step.description(), pattern.toText());
    }

    private Map<Integer, BrowserTraceEventRecord> mapEventsToSteps(
            List<BrowserTraceEventRecord> events,
            List<TraceStepNormalizer.CleanTraceStep> steps) {
        Map<Integer, BrowserTraceEventRecord> map = new HashMap<>();
        if (events == null || steps == null) return map;
        // 简单映射：按 relativeMs 近似匹配
        for (TraceStepNormalizer.CleanTraceStep step : steps) {
            if (step.relativeMs() == null) continue;
            BrowserTraceEventRecord bestMatch = null;
            long bestDelta = Long.MAX_VALUE;
            for (BrowserTraceEventRecord e : events) {
                if (e.relativeMs() == null) continue;
                long delta = Math.abs(e.relativeMs() - step.relativeMs());
                if (delta < bestDelta) {
                    bestDelta = delta;
                    bestMatch = e;
                }
            }
            if (bestMatch != null) {
                map.put(step.stepNo(), bestMatch);
            }
        }
        return map;
    }

    private String suggestStepTextRewrite(TraceStepNormalizer.CleanTraceStep step, BrowserTraceEventRecord event) {
        String desc = step.description();
        if (desc == null) return null;

        // 如果描述中有 objectLabel 但未体现行号定位，建议补充
        if (event != null && event.objectLabel() != null && !event.objectLabel().isBlank()) {
            String label = event.objectLabel();
            if (!desc.contains(label) && desc.contains("列表")) {
                return desc.replace("列表", "在 \"" + label + "\" 行");
            }
        }

        // 如果点击关闭按钮但弹窗标题有业务含义，建议补充
        if (desc.contains("点击\"关闭\"按钮") && event != null && event.dialogTitle() != null) {
            String dialog = event.dialogTitle();
            if (dialog.contains("取消") || dialog.contains("删除")) {
                return "发起" + dialog;
            }
        }

        return null;
    }

    private boolean isNoiseStep(TraceStepNormalizer.CleanTraceStep step, BrowserTraceEventRecord event) {
        String desc = step.description();
        if (desc == null) return false;
        // 输入法中间态特征
        if (INPUT_COMPOSITION_PATTERN.matcher(desc).find()) return true;
        // 纯焦点跳动（无业务含义的 click）
        if (desc.contains("点击空白处") || desc.contains("点击页面空白")) return true;
        // 事件层判断：拼音中间态输入
        if (event != null && event.valueSummary() != null
                && INPUT_COMPOSITION_PATTERN.matcher(event.valueSummary()).find()) {
            return true;
        }
        return false;
    }

    private boolean canMergeSteps(TraceStepNormalizer.CleanTraceStep a, TraceStepNormalizer.CleanTraceStep b) {
        if (a == null || b == null) return false;
        // 同 actor + 同 actionType + 描述高度相似
        if (!Objects.equals(a.actor(), b.actor())) return false;
        if (!Objects.equals(a.actionType(), b.actionType())) return false;
        String da = a.description();
        String db = b.description();
        if (da == null || db == null) return false;
        // 相邻重复 click 或连续输入
        if (da.equals(db)) return true;
        // 连续输入同一字段的中间态
        if (da.contains("输入") && db.contains("输入")) {
            String va = extractInputValue(da);
            String vb = extractInputValue(db);
            if (va != null && vb != null && (va.startsWith(vb) || vb.startsWith(va))) {
                return true;
            }
        }
        return false;
    }

    private String mergeStepTexts(TraceStepNormalizer.CleanTraceStep a, TraceStepNormalizer.CleanTraceStep b) {
        String da = a.description();
        String db = b.description();
        if (da == null) return db;
        if (db == null) return da;
        if (da.equals(db)) return da;
        // 输入合并：取较长值
        if (da.contains("输入") && db.contains("输入")) {
            String va = extractInputValue(da);
            String vb = extractInputValue(db);
            if (va != null && vb != null) {
                String longer = va.length() >= vb.length() ? va : vb;
                return da.replace(va, longer);
            }
        }
        return da + "；" + db;
    }

    private String extractInputValue(String description) {
        if (description == null) return null;
        int start = description.indexOf("\"");
        int end = description.lastIndexOf("\"");
        if (start >= 0 && end > start) {
            return description.substring(start + 1, end);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 模式学习辅助 — 从已确认修正沉淀轻量模式
    // ------------------------------------------------------------------

    /**
     * 基于轨迹组中已确认修正生成轻量模式记忆。
     *<p>
     * 只读取当前轨迹组中 status = CONFIRMED 的修正建议，
     * 按 (pageTitle, elementRole, correctionType, fromText) 维度聚合，
     * 沉淀为 trace_correction_pattern 表记录。
     *<p>
     * 模式只用于提升后续候选建议质量，不自动回写正式资产，
     * 不影响其他轨迹组的默认生成。
     *
     * @param traceGroupId 轨迹组 ID
     * @param user         当前用户
     * @return 新生成的模式数量
     */
    @Transactional
    public int learnPatternFromConfirmedSuggestions(Long traceGroupId, CurrentUser user) {
        // 先通过 traceGroupId 查询 projectId
        Long projectId = jdbc.sql("select project_id from browser_trace_group where id = :groupId")
                .param("groupId", traceGroupId)
                .query(Long.class).single();

        List<TraceCorrectionCandidateRecord> confirmed = jdbc.sql("""
                        select * from trace_correction_candidate
                        where trace_group_id = :groupId and status = 'CONFIRMED'
                        """)
                .param("groupId", traceGroupId)
                .query(this::mapRecord).list();

        if (confirmed.isEmpty()) return 0;

        // 读取已有的模式（用于去重和更新计数）
        List<TraceCorrectionPatternRecord> existingPatterns = jdbc.sql("""
                        select * from trace_correction_pattern
                        where project_id = :projectId
                        """).param("projectId", projectId)
                .query(this::mapPatternRecord).list();

        int created = 0;
        int updated = 0;
        LocalDateTime now = timeProvider.now();

        for (TraceCorrectionCandidateRecord c : confirmed) {
            String fromText = c.sourceText();
            String toText = isStepScopedCorrection(c) ? extractLearnableStepText(c) : effectiveValue(c);
            if (fromText == null || fromText.isBlank() || toText == null || toText.isBlank()) continue;
            if (isStepScopedCorrection(c) && isRiskyGenericStepRewrite(fromText, toText)) continue;

            // 查找是否已有相同模式
            TraceCorrectionPatternRecord existing = findMatchingPattern(existingPatterns, c, fromText, toText);

            if (existing != null) {
                // 更新计数和时间
                jdbcTemplate.update("""
                        update trace_correction_pattern set
                            confirmed_count = confirmed_count + 1,
                            last_confirmed_at = ?,
                            source_correction_ids = concat(source_correction_ids, ',', ?),
                            updated_at = ?
                        where id = ?
                        """, now, c.id(), now, existing.id());
                updated++;
            } else {
                // 创建新模式
                String pageTitle = extractPageTitle(c);
                String elementRole = extractElementRole(c);
                String dialogTitle = extractDialogTitle(c);
                String sectionTitle = extractSectionTitle(c);

                jdbcTemplate.update("""
                        insert into trace_correction_pattern(
                            project_id, page_title_keyword, element_role,
                            dialog_title_keyword, section_title_keyword,
                            correction_type, operation_type, from_text, to_text,
                            confirmed_count, last_confirmed_at, source_correction_ids,
                            created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                        """,
                        projectId, pageTitle, elementRole,
                        dialogTitle, sectionTitle,
                        c.correctionType(), c.operationType(), fromText, toText,
                        now, String.valueOf(c.id()), now, now);
                created++;
            }
        }

        refreshSemanticSnapshotQuietly(projectId);
        return created + updated;
    }

    StepTemplateParse parseTemplateStepText(String editedText) {
        if (editedText == null || editedText.isBlank()) {
            return StepTemplateParse.empty();
        }
        Matcher matcher = DYNAMIC_MARKER_PATTERN.matcher(editedText);
        StringBuilder display = new StringBuilder();
        StringBuilder template = new StringBuilder();
        AtomicInteger sequence = new AtomicInteger(0);
        Map<String, Integer> countsByType = new HashMap<>();
        int last = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            display.append(editedText, last, matcher.start());
            template.append(editedText, last, matcher.start());
            String dynamicText = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            String inner = dynamicText == null ? "" : dynamicText.trim();
            String slotType = inferDynamicSlotType(inner);
            int slotIndex = countsByType.merge(slotType, 1, Integer::sum);
            String placeholder = "{{" + slotType + "_" + slotIndex + "}}";
            display.append(inner);
            template.append(placeholder);
            last = matcher.end();
            sequence.incrementAndGet();
        }
        if (!found) {
            return new StepTemplateParse(editedText.trim(), editedText.trim(), false);
        }
        display.append(editedText.substring(last));
        template.append(editedText.substring(last));
        return new StepTemplateParse(display.toString().trim(), template.toString().trim(), true);
    }

    String renderTemplateStepText(String templateText, TraceStepNormalizer.CleanTraceStep step) {
        List<String> stepTexts = step == null || step.description() == null
                ? List.of()
                : List.of(step.description());
        return renderTemplateStepText(templateText, new StepRenderContext(step, stepTexts, List.of()));
    }

    private String renderTemplateStepText(String templateText, StepRenderContext context) {
        if (templateText == null || templateText.isBlank()) {
            return templateText;
        }
        Matcher matcher = TEMPLATE_PLACEHOLDER_PATTERN.matcher(templateText);
        if (!matcher.find()) {
            return templateText;
        }
        matcher.reset();
        StringBuilder rendered = new StringBuilder();
        int last = 0;
        Map<String, Integer> offsets = new HashMap<>();
        while (matcher.find()) {
            rendered.append(templateText, last, matcher.start());
            String placeholder = matcher.group(1);
            String slotType = placeholder.replaceAll("_\\d+$", "");
            int offset = offsets.getOrDefault(slotType, 0);
            List<String> values = extractDynamicValues(context, slotType);
            if (offset >= values.size()) {
                return null;
            }
            rendered.append(values.get(offset));
            offsets.put(slotType, offset + 1);
            last = matcher.end();
        }
        rendered.append(templateText.substring(last));
        return rendered.toString();
    }

    boolean isRiskyGenericStepRewrite(String fromText, String toText) {
        if (fromText == null || toText == null) {
            return false;
        }
        String from = fromText.trim();
        String to = toText.trim();
        if (from.isBlank() || to.isBlank()) {
            return false;
        }
        if (TEMPLATE_PLACEHOLDER_PATTERN.matcher(to).find()) {
            return false;
        }
        if (!GENERIC_CONFIRM_STEP_PATTERN.matcher(from).matches()) {
            return false;
        }
        return TEMPORAL_VALUE_PATTERN.matcher(to).matches();
    }

    private String extractLearnableStepText(TraceCorrectionCandidateRecord c) {
        String templateText = extractTemplateTextFromSnapshot(c.rawContextSnapshot());
        if (templateText != null && !templateText.isBlank()) {
            return templateText;
        }
        return effectiveStepText(c);
    }

    private String extractTemplateTextFromSnapshot(String rawSnapshot) {
        if (rawSnapshot == null || rawSnapshot.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(rawSnapshot, new TypeReference<>() {});
            Object type = payload.get("snapshotType");
            Object template = payload.get("templateStepText");
            if ("MANUAL_STEP_EDIT".equals(type) && template instanceof String templateText && !templateText.isBlank()) {
                return templateText;
            }
        } catch (Exception ignore) {
            // fall through
        }
        return null;
    }

    private String buildManualStepEditSnapshot(String sourceText, String displayText, String templateText) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotType", "MANUAL_STEP_EDIT");
            payload.put("sourceText", sourceText);
            payload.put("displayStepText", displayText);
            payload.put("templateStepText", templateText);
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "MANUAL_STEP_EDIT";
        }
    }

    private String inferDynamicSlotType(String text) {
        if (text == null || text.isBlank()) return "TEXT";
        if (DATETIME_PATTERN.matcher(text).matches()) return "DATETIME";
        if (DATE_PATTERN.matcher(text).matches()) return "DATE";
        if (TIME_PATTERN.matcher(text).matches()) return "TIME";
        if (EMAIL_PATTERN.matcher(text).matches()) return "EMAIL";
        if (PHONE_PATTERN.matcher(text).matches()) return "PHONE";
        if (looksLikeVerificationCode(text)) return "CODE";
        if (NUMBER_PATTERN.matcher(text).matches()) return "NUMBER";
        return "TEXT";
    }

    private StepRenderContext buildStepRenderContext(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            int stepIndex,
            BrowserTraceEventRecord currentEvent,
            List<BrowserTraceEventRecord> orderedEvents,
            Map<Long, Integer> eventIndexById) {
        List<String> texts = new ArrayList<>();
        List<String> eventTexts = new ArrayList<>();
        TraceStepNormalizer.CleanTraceStep currentStep = steps.get(stepIndex);
        appendIfMeaningful(texts, currentStep.description());
        for (int offset = 1; offset <= 2; offset++) {
            if (stepIndex - offset >= 0) {
                appendIfMeaningful(texts, steps.get(stepIndex - offset).description());
            }
            if (stepIndex + offset < steps.size()) {
                appendIfMeaningful(texts, steps.get(stepIndex + offset).description());
            }
        }
        if (currentEvent != null) {
            collectEventTexts(eventTexts, currentEvent);
            Integer eventIndex = currentEvent.id() == null ? null : eventIndexById.get(currentEvent.id());
            if (eventIndex != null) {
                for (int offset = 1; offset <= 2; offset++) {
                    if (eventIndex - offset >= 0) {
                        collectEventTexts(eventTexts, orderedEvents.get(eventIndex - offset));
                    }
                    if (eventIndex + offset < orderedEvents.size()) {
                        collectEventTexts(eventTexts, orderedEvents.get(eventIndex + offset));
                    }
                }
            }
        }
        return new StepRenderContext(currentStep, texts, eventTexts);
    }

    private List<String> extractDynamicValues(String description, String slotType) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        Pattern pattern = switch (slotType) {
            case "DATETIME" -> DATETIME_PATTERN;
            case "DATE" -> DATE_PATTERN;
            case "TIME" -> TIME_PATTERN;
            case "EMAIL" -> EMAIL_PATTERN;
            case "PHONE" -> PHONE_PATTERN;
            case "CODE" -> CODE_PATTERN;
            case "NUMBER" -> NUMBER_PATTERN;
            default -> Pattern.compile("[“\"']([^”\"']+)[”\"']");
        };
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(description);
        while (matcher.find()) {
            String value = matcher.group(matcher.groupCount() >= 1 ? 1 : 0);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        if (!values.isEmpty()) {
            return values;
        }
        if ("TEXT".equals(slotType)) {
            return List.of(description.trim());
        }
        return List.of();
    }

    private List<String> extractDynamicValues(StepRenderContext context, String slotType) {
        if (context == null) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> unique = new LinkedHashMap<>();
        List<String> dateValues = new ArrayList<>();
        List<String> timeValues = new ArrayList<>();
        for (String candidate : context.candidateTexts()) {
            for (String value : extractDynamicValues(candidate, slotType)) {
                unique.putIfAbsent(value, Boolean.TRUE);
            }
            if ("DATETIME".equals(slotType)) {
                for (String value : extractDynamicValues(candidate, "DATE")) {
                    if (!dateValues.contains(value)) {
                        dateValues.add(value);
                    }
                }
                for (String value : extractDynamicValues(candidate, "TIME")) {
                    if (!timeValues.contains(value)) {
                        timeValues.add(value);
                    }
                }
            }
        }
        if (!unique.isEmpty()) {
            return new ArrayList<>(unique.keySet());
        }
        if ("DATETIME".equals(slotType) && !dateValues.isEmpty() && !timeValues.isEmpty()) {
            return List.of(dateValues.get(0) + " " + timeValues.get(0));
        }
        if ("TEXT".equals(slotType) && context.step() != null && context.step().description() != null) {
            return List.of(context.step().description().trim());
        }
        return List.of();
    }

    private void collectEventTexts(List<String> target, BrowserTraceEventRecord event) {
        if (event == null) {
            return;
        }
        appendIfMeaningful(target, event.valueSummary());
        appendIfMeaningful(target, event.elementText());
        appendIfMeaningful(target, event.objectLabel());
        appendIfMeaningful(target, event.dialogTitle());
        appendIfMeaningful(target, event.sectionTitle());
        appendIfMeaningful(target, event.pageTitle());
        appendIfMeaningful(target, event.pageUrl());
    }

    private void appendIfMeaningful(List<String> target, String text) {
        if (text == null) {
            return;
        }
        String normalized = text.trim();
        if (!normalized.isBlank() && !target.contains(normalized)) {
            target.add(normalized);
        }
    }

    private boolean looksLikeVerificationCode(String text) {
        String normalized = text == null ? "" : text.trim();
        return normalized.matches("[A-Za-z0-9]{4,8}");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record StepRenderContext(
            TraceStepNormalizer.CleanTraceStep step,
            List<String> stepTexts,
            List<String> eventTexts) {
        List<String> candidateTexts() {
            List<String> merged = new ArrayList<>(stepTexts);
            merged.addAll(eventTexts);
            return merged;
        }
    }

    /**
     * 查询可用于提升候选建议质量的已有模式。
     *<p>
     * 按 (pageTitleKeyword, elementRole, correctionType) 维度匹配，
     * 返回 confirmed_count 最高的模式。
     */
    public List<TraceCorrectionPatternRecord> findPatternsForContext(
            Long projectId, String pageTitle, String elementRole, String correctionType) {
        StringBuilder sql = new StringBuilder("""
                select * from trace_correction_pattern
                where project_id = :projectId
                """);
        if (pageTitle != null && !pageTitle.isBlank()) {
            sql.append(" and (page_title_keyword is null or :pageTitle like concat('%', page_title_keyword, '%'))");
        }
        if (elementRole != null && !elementRole.isBlank()) {
            sql.append(" and (element_role is null or element_role = :elementRole)");
        }
        if (correctionType != null && !correctionType.isBlank()) {
            sql.append(" and correction_type = :correctionType");
        }
        sql.append(" order by confirmed_count desc, last_confirmed_at desc limit 10");

        var query = jdbc.sql(sql.toString()).param("projectId", projectId);
        if (pageTitle != null && !pageTitle.isBlank()) {
            query = query.param("pageTitle", pageTitle);
        }
        if (elementRole != null && !elementRole.isBlank()) {
            query = query.param("elementRole", elementRole);
        }
        if (correctionType != null && !correctionType.isBlank()) {
            query = query.param("correctionType", correctionType);
        }
        return query.query(this::mapPatternRecord).list();
    }

    private TraceCorrectionPatternRecord findMatchingPattern(
            List<TraceCorrectionPatternRecord> patterns,
            TraceCorrectionCandidateRecord c, String fromText, String toText) {
        for (TraceCorrectionPatternRecord p : patterns) {
            if (!Objects.equals(p.correctionType(), c.correctionType())) continue;
            if (!Objects.equals(p.fromText(), fromText)) continue;
            if (!Objects.equals(p.toText(), toText)) continue;
            return p;
        }
        return null;
    }

    private String extractPageTitle(TraceCorrectionCandidateRecord c) {
        // 从 raw_context_snapshot 中提取 page_title（简化实现）
        String raw = c.rawContextSnapshot();
        if (raw == null) return null;
        int idx = raw.indexOf("page=");
        if (idx >= 0) {
            int end = raw.indexOf('|', idx);
            if (end > idx) {
                String page = raw.substring(idx + 5, end).trim();
                // 取前 20 字作为关键词
                return page.length() > 20 ? page.substring(0, 20) : page;
            }
        }
        return null;
    }

    private String extractElementRole(TraceCorrectionCandidateRecord c) {
        // 简化：从 source_text 推断
        String source = c.sourceText();
        if (source == null) return null;
        if (source.contains("按钮")) return "button";
        if (source.contains("输入")) return "input";
        if (source.contains("勾选")) return "checkbox";
        return null;
    }

    private String extractDialogTitle(TraceCorrectionCandidateRecord c) {
        String raw = c.rawContextSnapshot();
        if (raw == null) return null;
        int idx = raw.indexOf("dialog=");
        if (idx >= 0) {
            int end = raw.indexOf('|', idx);
            if (end > idx) {
                return raw.substring(idx + 7, end).trim();
            }
        }
        return null;
    }

    private String extractSectionTitle(TraceCorrectionCandidateRecord c) {
        String raw = c.rawContextSnapshot();
        if (raw == null) return null;
        int idx = raw.indexOf("section=");
        if (idx >= 0) {
            int end = raw.indexOf('|', idx);
            if (end > idx) {
                return raw.substring(idx + 8, end).trim();
            }
        }
        return null;
    }

    private TraceCorrectionPatternRecord mapPatternRecord(ResultSet rs, int rowNum) throws SQLException {
        return new TraceCorrectionPatternRecord(
                rs.getLong("id"),
                getLongNullable(rs, "project_id"),
                rs.getString("page_url_pattern"),
                rs.getString("page_title_keyword"),
                rs.getString("element_text_pattern"),
                rs.getString("element_role"),
                rs.getString("dialog_title_keyword"),
                rs.getString("section_title_keyword"),
                rs.getString("correction_type"),
                rs.getString("operation_type"),
                rs.getString("from_text"),
                rs.getString("to_text"),
                rs.getInt("confirmed_count"),
                toLocalDateTime(rs, "last_confirmed_at"),
                rs.getString("source_correction_ids"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at")
        );
    }

    // ------------------------------------------------------------------
    // Row mapping
    // ------------------------------------------------------------------

    private TraceCorrectionCandidateRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new TraceCorrectionCandidateRecord(
                rs.getLong("id"),
                getLongNullable(rs, "project_id"),
                getLongNullable(rs, "trace_group_id"),
                getLongNullable(rs, "trace_session_id"),
                getLongNullable(rs, "issue_clip_id"),
                getLongNullable(rs, "trace_event_id"),
                getLongNullable(rs, "summary_id"),
                rs.getString("correction_type"),
                rs.getString("source_text"),
                rs.getString("candidate_value"),
                rs.getString("confirmed_value"),
                rs.getString("candidate_reason"),
                rs.getString("confidence_label"),
                rs.getString("status"),
                getIntNullable(rs, "step_no"),
                rs.getString("correction_scope"),
                rs.getString("operation_type"),
                rs.getString("candidate_step_text"),
                rs.getString("confirmed_step_text"),
                getIntNullable(rs, "related_step_no"),
                rs.getString("raw_context_snapshot"),
                getLongNullable(rs, "prompt_snapshot_id"),
                getLongNullable(rs, "context_manifest_id"),
                getLongNullable(rs, "llm_invocation_log_id"),
                getLongNullable(rs, "created_by"),
                getLongNullable(rs, "confirmed_by"),
                toLocalDateTime(rs, "confirmed_at"),
                getLongNullable(rs, "rejected_by"),
                toLocalDateTime(rs, "rejected_at"),
                rs.getString("rejected_reason"),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at")
        );
    }

    private Long getLongNullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer getIntNullable(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private void refreshSemanticSnapshotQuietly(Long projectId) {
        try {
            semanticContextService.refreshSnapshot(projectId);
        } catch (Exception ignored) {
            // 语义快照是附加缓存，不阻塞修正主链路
        }
    }

    record StepTemplateParse(String displayText, String templateText, boolean hasTemplateMarkers) {
        static StepTemplateParse empty() {
            return new StepTemplateParse("", "", false);
        }
    }

    // ------------------------------------------------------------------
    // Internal records
    // ------------------------------------------------------------------

    private record RuleCandidate(String type, Long eventId, String sourceText, String candidateValue, String reason) {}
    private record LlmCorrectionItem(String type, String sourceText, String candidateValue, String candidateReason, String confidenceLabel, Long traceEventId) {}
    private record CorrectionItem(String correctionType, String sourceText, String candidateValue, String candidateReason, String confidenceLabel, Long traceEventId) {}
    public record StepLevelCandidate(
            int stepNo, String correctionScope, String operationType,
            String originalStepText, String candidateStepText,
            Integer relatedStepNo, String reason) {}
}
