package com.company.aitest.generation.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.generation.DirectCaseGenerationService;
import com.company.aitest.generation.GenerationTaskService;
import com.company.aitest.llm.gateway.*;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.semantic.ProjectSemanticContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequirementAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RequirementAnalysisService.class);

    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int MAX_ANALYSIS_REQUIREMENT_CHARS = 8_000;
    private static final int MAX_ANALYSIS_TOM_CHARS = 8_000;
    private static final int MAX_PREVIOUS_ANSWERS_CHARS = 4_000;
    private static final int MAX_GENERATION_REQUIREMENT_CHARS = 6_000;
    private static final int MAX_GENERATION_ANALYSIS_CHARS = 10_000;
    private static final int MAX_GENERATION_TEST_POINTS_CHARS = 10_000;
    private static final int MAX_GENERATION_TOM_CHARS = 8_000;
    private static final int MAX_GENERATION_SMALL_SECTION_CHARS = 4_000;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final LlmGateway llmGateway;
    private final GenerationSessionService sessionService;
    private final GenerationMessageService messageService;
    private final GenerationAttachmentService attachmentService;
    private final MiniTomService miniTomService;
    private final GenerationTaskService taskService;
    private final DirectCaseGenerationService directCaseGenerationService;
    private final ProjectSemanticContextService semanticContextService;

    public RequirementAnalysisService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                       LlmGateway llmGateway, GenerationSessionService sessionService,
                                       GenerationMessageService messageService, GenerationAttachmentService attachmentService,
                                       MiniTomService miniTomService, GenerationTaskService taskService,
                                       DirectCaseGenerationService directCaseGenerationService,
                                       ProjectSemanticContextService semanticContextService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.llmGateway = llmGateway;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.miniTomService = miniTomService;
        this.taskService = taskService;
        this.directCaseGenerationService = directCaseGenerationService;
        this.semanticContextService = semanticContextService;
    }

    public RequirementAnalysisRecord analyze(Long sessionId, CurrentUser user) {
        var session = sessionService.get(null, sessionId, user);
        Long projectId = session.projectId();

        if (session.modelConfigId() == null) {
            throw new BusinessException("请先配置模型（点击右上角设置选择模型配置）");
        }

        // 1. Build cumulative requirement text from user messages
        var messages = messageService.listMessages(sessionId);
        StringBuilder reqText = new StringBuilder();
        for (var msg : messages) {
            if ("USER".equals(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                reqText.append(msg.content()).append("\n");
            }
        }

        // 2. Include parsed attachment content
        var attachments = attachmentService.listBySession(sessionId);
        for (var att : attachments) {
            if ("PARSED".equals(att.parseStatus()) && att.parsedContent() != null) {
                reqText.append("\n[附件: ").append(att.fileName()).append("]\n").append(att.parsedContent());
            }
            if (att.visionResult() != null) {
                reqText.append("\n[图片理解: ").append(att.fileName()).append("]\n").append(att.visionResult());
            }
        }

        String requirementText = reqText.toString().trim();
        if (requirementText.isBlank()) {
            throw new BusinessException("请先输入需求内容");
        }

        // 3. Build TOM context if needed
        String tomSnapshot = null;
        if (session.useMiniTom()) {
            try {
                var tomResult = miniTomService.buildTestScope(projectId, requirementText, session.modelConfigId(), user);
                tomSnapshot = toJson(tomResult);
            } catch (Exception e) {
                log.warn("TOM context build failed: {}", e.getMessage());
            }
        }
        ProjectSemanticContextService.BuildResult semanticContext =
                buildSemanticEvidence(projectId, requirementText);

        // 4. Include previous clarification answers if re-analyzing
        String previousAnswers = null;
        var prevAnalysis = getLatestAnalysis(sessionId);
        if (prevAnalysis != null && prevAnalysis.clarificationAnswers() != null
                && !prevAnalysis.clarificationAnswers().isBlank() && !"[]".equals(prevAnalysis.clarificationAnswers())) {
            previousAnswers = prevAnalysis.clarificationAnswers();
        }

        // 5. Ensure internal task exists for LLM gateway
        Long taskId = session.executionTaskId();
        if (taskId == null) {
            var taskCmd = new GenerationTaskService.CreateTaskCommand(
                    session.sessionTitle(), requirementText, session.modelConfigId(),
                    session.promptSnapshot(), session.useMiniTom() ? "MINI_TOM" : "DIRECT",
                    session.useMiniTom()
            );
            var task = taskService.create(projectId, taskCmd, user);
            taskId = task.id();
            sessionService.updateExecutionTaskId(sessionId, taskId);
        }

        // 6. Reserve a unique analysis version for this round to avoid duplicate inserts under concurrent re-analyze.
        int newVersion = sessionService.reserveNextAnalysisVersion(sessionId);

        // 7. Call LLM for requirement analysis + test point generation
        String systemPrompt = buildAnalysisSystemPrompt();
        String userPrompt = buildAnalysisUserPrompt(requirementText, tomSnapshot, semanticContext, previousAnswers);

        var request = new LlmInvocationRequest(
                UUID.randomUUID().toString(), user.id(), projectId, taskId,
                "REQUIREMENT_ANALYSIS", LlmStage.REQ_CLARIFY,
                session.modelConfigId(), session.promptTemplateId(), null,
                Map.of(), systemPrompt, userPrompt, null, 16384
        );

        var response = llmGateway.invoke(request);
        if (response.status() != LlmInvocationStatus.OK) {
            throw new BusinessException("需求分析失败: " + response.errorMessage());
        }

        // 6. Parse LLM response
        String llmOutput = response.content();
        String clarificationQuestions = normalizeJsonColumn(extractJson(llmOutput, "clarification_questions"));
        String analysisResult = enrichAnalysisResult(
                normalizeJsonColumn(extractJson(llmOutput, "analysis")),
                semanticContext,
                clarificationQuestions
        );
        String testPoints = enrichTestPoints(normalizeJsonColumn(extractJson(llmOutput, "test_points")), semanticContext);
        String assumptions = normalizeJsonColumn(extractJson(llmOutput, "assumptions"));

        // 7. Save analysis record
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO requirement_analysis(session_id, version, sub_version, requirement_text, analysis_result, tom_scope_snapshot, clarification_questions, assumptions, test_points, affected_cases, change_scope, new_cases_needed, status, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 'NEED_CONFIRMATION', ?, ?)
                """, sessionId, newVersion, requirementText, analysisResult, tomSnapshot,
                clarificationQuestions, assumptions, testPoints, now, now);

        // 8. Append assistant message
        String summary = "需求分析完成（v" + newVersion + "）。";
        if (clarificationQuestions != null && !clarificationQuestions.isBlank() && !"[]".equals(clarificationQuestions)) {
            summary += "\n\n请确认以上分析是否正确？有什么需要补充或修改的内容请输入。\n你也可以点击「跳过确认，直接生成用例」。";
        }
        messageService.appendAssistantMessage(sessionId, summary, llmOutput, "REQ_ANALYSIS", newVersion);

        return getAnalysis(sessionId, newVersion);
    }

    // =====================================================================
    // 增量分析
    // =====================================================================

    private static final int MAX_INCREMENTAL_VERSIONS = 3;

    /**
     * 增量分析：基于上一版已确认的分析结果 + 用户补充内容，输出完整更新后的分析 JSON。
     * 版本号规则：全量 v1/v2/v3，增量 v3.1/v3.2/v3.3，最多 3 次增量。
     */
    @Transactional
    public RequirementAnalysisRecord incrementalAnalyze(Long sessionId, String supplementContent, CurrentUser user) {
        var session = sessionService.get(null, sessionId, user);
        if (session == null) throw new BusinessException("会话不存在");
        Long projectId = session.projectId();

        if (supplementContent == null || supplementContent.isBlank()) {
            throw new BusinessException("请输入补充或修改内容");
        }

        // 1. 获取上一版分析结果
        var previousAnalysis = getLatestAnalysis(sessionId);
        if (previousAnalysis == null || previousAnalysis.analysisResult() == null) {
            throw new BusinessException("没有已确认的分析结果，请先进行全量分析");
        }

        // 2. 检查增量次数限制
        int currentSubVersion = previousAnalysis.subVersion();
        if (currentSubVersion >= MAX_INCREMENTAL_VERSIONS) {
            throw new BusinessException("已进行 " + MAX_INCREMENTAL_VERSIONS + " 次增量分析，建议全量分析以确保准确性。请重新分析。");
        }
        int newSubVersion = currentSubVersion + 1;

        // 3. Build semantic context — 合并上一版需求、本次补充、已有澄清答案
        StringBuilder semanticInput = new StringBuilder();
        semanticInput.append(previousAnalysis.requirementText() != null ? previousAnalysis.requirementText() : "");
        semanticInput.append("\n").append(supplementContent);
        if (previousAnalysis.clarificationAnswers() != null
                && !previousAnalysis.clarificationAnswers().isBlank()
                && !"[]".equals(previousAnalysis.clarificationAnswers())) {
            semanticInput.append("\n").append(previousAnalysis.clarificationAnswers());
        }
        ProjectSemanticContextService.BuildResult semanticContext =
                buildSemanticEvidence(projectId, semanticInput.toString().trim());

        // 4. Ensure internal task exists
        Long taskId = session.executionTaskId();
        if (taskId == null) {
            var taskCmd = new GenerationTaskService.CreateTaskCommand(
                    session.sessionTitle(), previousAnalysis.requirementText(), session.modelConfigId(),
                    session.promptSnapshot(), session.useMiniTom() ? "MINI_TOM" : "DIRECT",
                    session.useMiniTom()
            );
            var task = taskService.create(projectId, taskCmd, user);
            taskId = task.id();
            sessionService.updateExecutionTaskId(sessionId, taskId);
        }

        // 5. Build prompts — system prompt same as full, user prompt includes previous result + supplement
        String systemPrompt = buildIncrementalSystemPrompt();
        String userPrompt = buildIncrementalUserPrompt(
                previousAnalysis.requirementText(),
                previousAnalysis.analysisResult(),
                previousAnalysis.testPoints(),
                previousAnalysis.clarificationQuestions(),
                previousAnalysis.clarificationAnswers(),
                previousAnalysis.assumptions(),
                previousAnalysis.tomScopeSnapshot(),
                semanticContext,
                supplementContent,
                sessionId
        );

        // 6. Call LLM
        var request = new LlmInvocationRequest(
                UUID.randomUUID().toString(), user.id(), projectId, taskId,
                "REQUIREMENT_ANALYSIS_INCREMENTAL", LlmStage.REQ_CLARIFY,
                session.modelConfigId(), session.promptTemplateId(), null,
                Map.of(), systemPrompt, userPrompt, null, 16384
        );

        var response = llmGateway.invoke(request);
        if (response.status() != LlmInvocationStatus.OK) {
            throw new BusinessException("增量分析失败: " + response.errorMessage());
        }

        // 7. Parse LLM response
        String llmOutput = response.content();
        String clarificationQuestions = normalizeJsonColumn(extractJson(llmOutput, "clarification_questions"));
        String analysisResult = enrichAnalysisResult(
                normalizeJsonColumn(extractJson(llmOutput, "analysis")),
                semanticContext,
                clarificationQuestions
        );
        String testPoints = enrichTestPoints(normalizeJsonColumn(extractJson(llmOutput, "test_points")), semanticContext);
        String assumptions = normalizeJsonColumn(extractJson(llmOutput, "assumptions"));

        String affectedCases = normalizeJsonColumn(extractJson(llmOutput, "affected_cases"));
        String changeScope = extractPlainText(llmOutput, "change_scope");
        String newCasesNeeded = normalizeJsonColumn(extractJson(llmOutput, "new_cases_needed"));

        // 8. Save with sub_version
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO requirement_analysis(session_id, version, sub_version, requirement_text, analysis_result, tom_scope_snapshot, clarification_questions, assumptions, test_points, affected_cases, change_scope, new_cases_needed, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEED_CONFIRMATION', ?, ?)
                """, sessionId, previousAnalysis.version(), newSubVersion,
                previousAnalysis.requirementText(), analysisResult, previousAnalysis.tomScopeSnapshot(),
                clarificationQuestions, assumptions, testPoints, affectedCases, changeScope, newCasesNeeded, now, now);

        // 9. Append assistant message
        String versionLabel = previousAnalysis.version() + "." + newSubVersion;
        String summary = "需求分析增量更新（v" + versionLabel + "）。";
        if (clarificationQuestions != null && !clarificationQuestions.isBlank() && !"[]".equals(clarificationQuestions)) {
            summary += "\n\n请确认以上分析是否正确？有什么需要补充或修改的内容请输入。\n你也可以点击「跳过确认，直接生成用例」。";
        } else {
            summary += "\n\n请确认分析结果。如果需要全量重新分析，请输入「重新分析」。";
        }
        messageService.appendAssistantMessage(sessionId, summary, llmOutput, "REQ_ANALYSIS", previousAnalysis.version());

        return getAnalysis(sessionId, previousAnalysis.version());
    }

    /**
     * 检查是否可以进行增量分析（未超过次数限制）。
     */
    public boolean canIncrementalAnalyze(Long sessionId) {
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) return false;
        return latest.subVersion() < MAX_INCREMENTAL_VERSIONS;
    }

    private String buildIncrementalSystemPrompt() {
        return buildAnalysisSystemPrompt() + """

                【增量分析特别指令】
                你正在进行增量分析。以下是上一版已确认的完整分析结果和当前已有测试用例列表，用户对其提出了补充/修改。
                规则：
                - 已确认的内容保持不变，只修改用户明确要求变更的部分
                - 如果用户补充了新的模块/页面，追加到 affected_modules / affected_pages
                - 如果用户修改了描述，直接替换
                - 如果用户取消了某个模块/页面，从影响范围中移除
                - 输出完整的需求分析 JSON（格式与全量分析一致，不要只输出变更部分）

                额外输出字段：
                - "affected_cases": 基于本次变更，判断哪些已有测试点/用例受影响。
                  必须参照"当前已有测试用例列表"中的实际用例标题进行匹配。
                  如果变更内容影响了现有用例的标题、模块、步骤或预期结果，则标记为受影响。
                  输出格式：[{"title": "已有用例标题", "reason": "变更原因", "confidence": 0.9}]
                  如果所有已有用例都不受影响，返回空数组 []。
                - "new_cases_needed": 如果变更引入了当前已有测试用例列表未覆盖的新测试点，列出需要新增的用例。
                  输出格式：[{"title": "新用例标题", "module_name": "模块", "description": "用例描述"}]
                  如果没有新的测试点需要新增用例，返回空数组 []。
                - "change_scope": "MINOR" 或 "MAJOR"
                  MINOR = 只改了描述/属性，不影响测试点结构
                  MAJOR = 新增了模块/页面/流程/角色
                """;
    }

    private String buildIncrementalUserPrompt(String requirementText, String previousAnalysisResult,
                                               String previousTestPoints, String previousClarificationQuestions,
                                               String previousClarificationAnswers, String previousAssumptions,
                                               String tomScopeSnapshot,
                                               ProjectSemanticContextService.BuildResult semanticContext,
                                               String supplementContent,
                                               Long sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前需求\n").append(clipForPrompt(requirementText, MAX_ANALYSIS_REQUIREMENT_CHARS));
        if (tomScopeSnapshot != null && !tomScopeSnapshot.isBlank()) {
            sb.append("\n\n## TOM 上下文\n").append(clipForPrompt(tomScopeSnapshot, MAX_ANALYSIS_TOM_CHARS));
        }
        if (semanticContext != null && semanticContext.promptSection() != null
                && !semanticContext.promptSection().isBlank()) {
            sb.append("\n\n## 项目证据上下文")
                    .append(semanticContext.promptSection());
        }
        sb.append("\n\n## 上一版已确认的分析结果\n")
                .append(clipForPrompt(previousAnalysisResult, 6000));
        if (previousTestPoints != null && !previousTestPoints.isBlank()) {
            sb.append("\n\n## 上一版测试点\n").append(clipForPrompt(previousTestPoints, 4000));
        }
        // 传入已有的用例列表，让 LLM 能匹配 affected_cases
        String existingCasesList = loadExistingCasesSummary(sessionId);
        if (existingCasesList != null && !existingCasesList.isBlank()) {
            sb.append("\n\n## 当前已有测试用例列表\n").append(existingCasesList);
        }
        if (previousClarificationQuestions != null && !previousClarificationQuestions.isBlank()
                && !"[]".equals(previousClarificationQuestions)) {
            sb.append("\n\n## 上一版澄清问题\n").append(clipForPrompt(previousClarificationQuestions, 2000));
        }
        if (previousClarificationAnswers != null && !previousClarificationAnswers.isBlank()
                && !"[]".equals(previousClarificationAnswers)) {
            sb.append("\n\n## 用户已回答的澄清\n").append(clipForPrompt(previousClarificationAnswers, 2000));
        }
        if (previousAssumptions != null && !previousAssumptions.isBlank()
                && !"[]".equals(previousAssumptions)) {
            sb.append("\n\n## 上一版假设\n").append(clipForPrompt(previousAssumptions, 1000));
        }
        sb.append("\n\n## 用户本次补充/修改\n").append(supplementContent);
        sb.append("\n\n请在上一版分析基础上，根据用户补充/修改内容，输出完整更新后的分析结果。已确认的内容保持不变。");
        return sb.toString();
    }

    /**
     * 加载已有用例摘要（标题+模块），供增量分析时 LLM 匹配 affected_cases。
     */
    private String loadExistingCasesSummary(Long sessionId) {
        try {
            var rows = jdbc.sql("SELECT case_title, module_name FROM test_case_draft WHERE session_id = :sid ORDER BY id")
                    .param("sid", sessionId)
                    .query((rs, rowNum) -> "- " + rs.getString("case_title") + "（模块：" + rs.getString("module_name") + "）")
                    .list();
            if (rows.isEmpty()) return null;
            return String.join("\n", rows);
        } catch (Exception e) {
            return null;
        }
    }

    public void submitAnswer(Long sessionId, int questionIndex, String answer, CurrentUser user) {
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) throw new BusinessException("没有待确认的分析结果");

        String existingAnswers = latest.clarificationAnswers();
        String newAnswer = "{\"index\":" + questionIndex + ",\"answer\":\"" + escapeJson(answer) + "\"}";
        String merged;
        if (existingAnswers == null || existingAnswers.isBlank() || "[]".equals(existingAnswers)) {
            merged = "[" + newAnswer + "]";
        } else {
            merged = existingAnswers.substring(0, existingAnswers.length() - 1) + "," + newAnswer + "]";
        }

        jdbcTemplate.update("UPDATE requirement_analysis SET clarification_answers = ?, updated_at = ? WHERE id = ?",
                merged, timeProvider.now(), latest.id());
    }

    public List<String> skipClarification(Long sessionId, CurrentUser user) {
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) throw new BusinessException("没有待确认的分析结果");

        // Mark as SKIPPED
        jdbcTemplate.update("UPDATE requirement_analysis SET status = 'SKIPPED', updated_at = ? WHERE id = ?",
                timeProvider.now(), latest.id());

        // Collect assumptions from unanswered questions
        List<String> assumptions = new ArrayList<>();
        if (latest.clarificationQuestions() != null) {
            assumptions.add("以下待确认事项已跳过确认，生成的用例基于假设：" + latest.clarificationQuestions());
        }

        messageService.appendSystemMessage(sessionId, "已跳过确认，将直接生成用例。未确认事项将写入 assumptions。");
        return assumptions;
    }

    public RequirementAnalysisRecord confirmAndGenerate(Long sessionId, CurrentUser user) {
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) throw new BusinessException("没有分析结果");

        jdbcTemplate.update("UPDATE requirement_analysis SET status = 'CONFIRMED', updated_at = ? WHERE id = ?",
                timeProvider.now(), latest.id());

        return doGenerate(sessionId, user);
    }

    public RequirementAnalysisRecord skipConfirmAndGenerate(Long sessionId, CurrentUser user) {
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) throw new BusinessException("没有分析结果");

        jdbcTemplate.update("UPDATE requirement_analysis SET status = 'SKIPPED', updated_at = ? WHERE id = ?",
                timeProvider.now(), latest.id());

        return doGenerate(sessionId, user);
    }

    public void confirmAnalysis(Long sessionId, int version) {
        jdbcTemplate.update("UPDATE requirement_analysis SET status = 'CONFIRMED', updated_at = ? WHERE session_id = ? AND version = ?",
                timeProvider.now(), sessionId, version);
    }

    public void skipAnalysis(Long sessionId, int version) {
        jdbcTemplate.update("UPDATE requirement_analysis SET status = 'SKIPPED', updated_at = ? WHERE session_id = ? AND version = ?",
                timeProvider.now(), sessionId, version);
    }

    // =====================================================================
    // 增量生成
    // =====================================================================

    @Transactional
    public void incrementalGenerate(Long sessionId, List<Integer> selectedDraftIds, CurrentUser user) {
        var session = sessionService.get(null, sessionId, user);
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) throw new BusinessException("没有分析结果");

        for (Integer draftId : selectedDraftIds) {
            // 读取原始草稿
            var draft = jdbc.sql("SELECT * FROM test_case_draft WHERE id = :id AND session_id = :sid")
                    .param("id", draftId.longValue()).param("sid", sessionId)
                    .query((rs, rowNum) -> new DraftRow(
                            rs.getLong("id"),
                            rs.getString("case_title"),
                            rs.getString("module_name"),
                            rs.getString("precondition"),
                            rs.getString("steps"),
                            rs.getString("expected_result"),
                            rs.getString("priority"),
                            rs.getString("source_refs_json"),
                            rs.getInt("analysis_version")
                    )).list();
            if (draft.isEmpty()) continue;
            var d = draft.get(0);

            // 构建增量生成 prompt
            String systemPrompt = """
                    你是一个测试用例更新器。根据新的需求分析结果，更新指定的测试用例。
                    只修改受变更影响的字段，其他保持不变。
                    输出完整的用例 JSON（与原始格式一致）：
                    {
                      "case_title": "用例标题",
                      "module_name": "模块名",
                      "precondition": "前置条件",
                      "steps": "测试步骤",
                      "expected_result": "预期结果",
                      "priority": "优先级"
                    }
                    """;
            String userPrompt = """
                    【原始用例】
                    标题: %s
                    模块: %s
                    前置条件: %s
                    步骤: %s
                    预期结果: %s
                    优先级: %s

                    【变更后的需求分析结果】
                    %s

                    【变更说明】
                    %s

                    请输出更新后的完整用例 JSON。只修改受变更影响的字段。
                    """.formatted(
                    d.caseTitle, d.moduleName, d.precondition, d.steps, d.expectedResult, d.priority,
                    clipForPrompt(latest.analysisResult(), 6000),
                    clipForPrompt(latest.testPoints(), 4000)
            );

            // 调用 LLM
            var request = new LlmInvocationRequest(
                    UUID.randomUUID().toString(), user.id(), session.projectId(), session.executionTaskId(),
                    "INCREMENTAL_CASE_GEN", LlmStage.TEST_CASE_GEN,
                    session.modelConfigId(), null, null, Map.of(),
                    systemPrompt, userPrompt, null, 8192
            );
            var response = llmGateway.invoke(request);
            if (response.status() != LlmInvocationStatus.OK) {
                log.warn("增量生成用例失败 draftId={}: {}", draftId, response.errorMessage());
                continue;
            }

            // 解析并更新
            try {
                var root = objectMapper.readTree(response.content());
                String newTitle = root.has("case_title") ? root.get("case_title").asText(d.caseTitle) : d.caseTitle;
                String newModule = root.has("module_name") ? root.get("module_name").asText(d.moduleName) : d.moduleName;
                String newPrecond = root.has("precondition") ? root.get("precondition").asText(d.precondition) : d.precondition;
                String newSteps = root.has("steps") ? root.get("steps").asText(d.steps) : d.steps;
                String newExpected = root.has("expected_result") ? root.get("expected_result").asText(d.expectedResult) : d.expectedResult;
                String newPriority = root.has("priority") ? root.get("priority").asText(d.priority) : d.priority;

                jdbcTemplate.update("""
                        UPDATE test_case_draft SET
                            case_title = ?, module_name = ?, precondition = ?,
                            steps = ?, expected_result = ?, priority = ?,
                            analysis_version = ?, updated_at = ?
                        WHERE id = ?
                        """, newTitle, newModule, newPrecond, newSteps, newExpected, newPriority,
                        latest.version(), timeProvider.now(), d.id);
            } catch (Exception e) {
                log.warn("解析增量生成结果失败 draftId={}: {}", draftId, e.getMessage());
            }
        }
    }

    private record DraftRow(Long id, String caseTitle, String moduleName, String precondition,
                            String steps, String expectedResult, String priority,
                            String sourceRefsJson, int analysisVersion) {}

    /**
     * 完整性检查：比对 analysis.testPoints 中的测试点标题与已生成用例的 sourceTestPoint，
     * 自动为未覆盖的测试点补充用例。
     */
    private void supplementMissingCases(Long sessionId, RequirementAnalysisRecord analysis, CurrentUser user) {
        if (analysis.testPoints() == null || analysis.testPoints().isBlank()) return;

        // 1. 提取分析中的测试点标题
        List<String> analysisTitles = new ArrayList<>();
        try {
            var root = objectMapper.readTree(analysis.testPoints());
            if (root.isArray()) {
                for (var node : root) {
                    String title = node.has("title") ? node.get("title").asText("") : "";
                    if (!title.isBlank()) analysisTitles.add(title);
                }
            }
        } catch (Exception ignored) {}

        if (analysisTitles.isEmpty()) return;

        // 2. 查询当前分析版本的草稿的 sourceTestPoint（兼容多种来源字段）
        // 按 analysis_version 过滤，避免历史草稿误判覆盖
        List<String> generatedTitles = jdbc.sql(
                "SELECT source_refs_json, case_status FROM test_case_draft WHERE session_id = :sid AND analysis_version = :aver")
                .param("sid", sessionId)
                .param("aver", analysis.version())
                .query((rs, rowNum) -> {
                    String status = rs.getString("case_status");
                    if ("DEPRECATED".equals(status)) return "";
                    String json = rs.getString("source_refs_json");
                    if (json == null || json.isBlank()) return "";
                    try {
                        var ref = objectMapper.readTree(json);
                        // 兼容三种字段路径
                        String tp = ref.has("sourceTestPoint") ? ref.get("sourceTestPoint").asText("") : "";
                        if (tp.isBlank() && ref.has("generatorRefs")) {
                            var genRefs = ref.get("generatorRefs");
                            if (genRefs.has("sourceTestPoint")) tp = genRefs.get("sourceTestPoint").asText("");
                        }
                        if (tp.isBlank() && ref.has("sourceTestPoints")) {
                            var tps = ref.get("sourceTestPoints");
                            if (tps.isArray() && tps.size() > 0) tp = tps.get(0).asText("");
                        }
                        return tp;
                    } catch (Exception e) { return ""; }
                })
                .list().stream().filter(t -> !t.isBlank()).toList();

        // 3. 找出未覆盖的测试点
        Set<String> generatedSet = new HashSet<>(generatedTitles);
        List<String> uncovered = analysisTitles.stream()
                .filter(t -> generatedSet.stream().noneMatch(g -> g.contains(t) || t.contains(g)))
                .toList();

        if (uncovered.isEmpty()) return;

        log.info("首次生成后发现 {} 个未覆盖的测试点，自动补充", uncovered.size());

        // 4. 构造 new_cases_needed JSON 并调用 autoGenerateNewCases
        List<Map<String, String>> missingCases = new ArrayList<>();
        for (String title : uncovered) {
            missingCases.add(Map.of("title", title, "module_name", "", "description", title));
        }
        try {
            String newCasesJson = objectMapper.writeValueAsString(missingCases);
            RequirementAnalysisRecord updatedAnalysis = new RequirementAnalysisRecord(
                    analysis.id(), analysis.sessionId(), analysis.version(), analysis.subVersion(),
                    analysis.requirementText(), analysis.analysisResult(), analysis.tomScopeSnapshot(),
                    analysis.clarificationQuestions(), analysis.clarificationAnswers(), analysis.assumptions(),
                    analysis.testPoints(), analysis.affectedCases(), analysis.changeScope(),
                    newCasesJson, analysis.status(), analysis.createdAt(), analysis.updatedAt()
            );
            autoGenerateNewCases(sessionId, updatedAnalysis, user);
        } catch (Exception e) {
            log.warn("自动补充缺失用例失败: {}", e.getMessage());
        }
    }

    /**
     * 增量分析后，自动为 new_cases_needed 中的新测试点生成用例草稿。
     * 一次性 LLM 调用生成所有新用例，避免逐条调用。
     */
    @Transactional
    public void autoGenerateNewCases(Long sessionId, RequirementAnalysisRecord analysis, CurrentUser user) {
        if (analysis.newCasesNeeded() == null || analysis.newCasesNeeded().isBlank()
                || "[]".equals(analysis.newCasesNeeded())) return;

        List<Map<String, String>> newCases;
        try {
            newCases = objectMapper.readValue(analysis.newCasesNeeded(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 new_cases_needed 失败: {}", e.getMessage());
            return;
        }
        if (newCases.isEmpty()) return;

        var session = sessionService.get(null, sessionId, user);

        // 构建 prompt：一次 LLM 调用生成所有新用例
        StringBuilder caseList = new StringBuilder();
        for (int i = 0; i < newCases.size(); i++) {
            var c = newCases.get(i);
            caseList.append(i + 1).append(". 标题: ").append(c.getOrDefault("title", ""))
                    .append(" | 模块: ").append(c.getOrDefault("module_name", ""))
                    .append(" | 描述: ").append(c.getOrDefault("description", "")).append("\n");
        }

        String systemPrompt = """
                你是资深测试工程师。请为以下新测试点批量生成测试用例草稿。
                输出 JSON 数组，每个对象字段：caseTitle, moduleName, precondition, steps, expectedResult, priority。
                steps 和 expectedResult 必须按编号格式，步骤和预期一一对应。
                """;
        String userPrompt = """
                【需求分析结果】
                %s

                【需要新增的测试点】
                %s

                请为每个测试点生成一条完整的测试用例。
                """.formatted(
                clipForPrompt(analysis.analysisResult(), 6000),
                caseList.toString()
        );

        var request = new LlmInvocationRequest(
                UUID.randomUUID().toString(), user.id(), session.projectId(), session.executionTaskId(),
                "AUTO_GEN_NEW_CASES", LlmStage.TEST_CASE_GEN,
                session.modelConfigId(), null, null, Map.of(),
                systemPrompt, userPrompt, null, 16384
        );

        var response = llmGateway.invoke(request);
        if (response.status() != LlmInvocationStatus.OK) {
            log.warn("自动批量生成新用例失败: {}", response.errorMessage());
            return;
        }

        // 解析并插入草稿
        try {
            String output = response.content();
            String jsonText = extractJsonArray(output);
            List<Map<String, Object>> rows = objectMapper.readValue(jsonText,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});

            LocalDateTime now = timeProvider.now();
            int existingDrafts = jdbc.sql("SELECT COUNT(*) FROM test_case_draft WHERE session_id = :sid")
                    .param("sid", sessionId).query(Integer.class).single();

            int idx = existingDrafts + 1;
            for (Map<String, Object> row : rows) {
                String caseNo = "TC-" + sessionId + "-NEW-" + idx++;
                String sourceTestPoint = strVal(row, "title", "新用例");
                String sourceRefsJson = "{\"sourceTestPoint\":\"" + sourceTestPoint.replace("\"", "\\\"") + "\",\"generatorRefs\":{\"sourceTestPoint\":\"" + sourceTestPoint.replace("\"", "\\\"") + "\"}}";
                jdbcTemplate.update("""
                        INSERT INTO test_case_draft(session_id, analysis_id, analysis_version,
                            case_no, case_title, module_name, precondition,
                            steps, expected_result, priority, case_type, case_status,
                            source_refs_json, quality_status, created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FUNCTIONAL', 'DRAFT', ?, 'LOW_EVIDENCE', ?, ?, ?)
                        """, sessionId, analysis.id(), analysis.version(),
                        caseNo,
                        strVal(row, "caseTitle", "新用例"),
                        strVal(row, "moduleName", "默认模块"),
                        strVal(row, "precondition", ""),
                        strVal(row, "steps", ""),
                        strVal(row, "expectedResult", ""),
                        strVal(row, "priority", "P2"),
                        sourceRefsJson,
                        user.id(), now, now);
            }
            log.info("自动为 {} 个新测试点生成了 {} 条用例", newCases.size(), rows.size());
        } catch (Exception e) {
            log.warn("解析批量生成结果失败: {}", e.getMessage());
        }
    }

    private String extractJsonArray(String raw) {
        if (raw == null) return "[]";
        String text = raw.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return "[]";
    }

    private String strVal(Map<String, Object> row, String key, String defaultVal) {
        Object v = row.get(key);
        return v != null ? String.valueOf(v) : defaultVal;
    }

    public RequirementAnalysisRecord doGenerate(Long sessionId, CurrentUser user) {
        var session = sessionService.get(null, sessionId, user);
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) {
            throw new BusinessException("没有分析结果");
        }

        sessionService.updateStatus(sessionId, "GENERATING");

        try {
            String generationRequirementText = buildGenerationRequirementText(latest);
            String generationPromptSnapshot = buildGenerationPromptSnapshot(session.promptSnapshot(), latest);

            // Create internal generation task
            var cmd = new GenerationTaskService.CreateTaskCommand(
                    session.sessionTitle(),
                    generationRequirementText,
                    session.modelConfigId(),
                    generationPromptSnapshot,
                    session.useMiniTom() ? "MINI_TOM" : "DIRECT",
                    session.useMiniTom()
            );
            var task = taskService.create(session.projectId(), cmd, user);
            sessionService.updateExecutionTaskId(sessionId, task.id());

            // Generate test points and cases. User-facing progress/error messages are appended by ConversationOrchestrator.
            var result = directCaseGenerationService.generateFromTask(session.projectId(), task.id(), user);
            // Link drafts to session and keep per-case source refs from the generator.
            applyDraftEvidenceLinks(task.id(), sessionId, latest, result);

            // 完整性检查：比对 test_points 与已生成用例，自动补充缺失的
            supplementMissingCases(sessionId, latest, user);

            sessionService.updateStatus(sessionId, "COMPLETED");

            // Update analysis status
            jdbcTemplate.update("UPDATE requirement_analysis SET status = 'GENERATED', updated_at = ? WHERE id = ?",
                    timeProvider.now(), latest.id());

            return getLatestAnalysis(sessionId);
        } catch (Exception e) {
            sessionService.updateStatus(sessionId, "ACTIVE");
            throw e;
        }
    }

    public RequirementAnalysisRecord getLatestAnalysis(Long sessionId) {
        var list = jdbc.sql("SELECT * FROM requirement_analysis WHERE session_id = :sid ORDER BY version DESC LIMIT 1")
                .param("sid", sessionId).query(this::map).list();
        return list.isEmpty() ? null : list.get(0);
    }

    public List<RequirementAnalysisRecord> listAnalyses(Long sessionId) {
        return jdbc.sql("SELECT * FROM requirement_analysis WHERE session_id = :sid ORDER BY version DESC")
                .param("sid", sessionId).query(this::map).list();
    }

    public RequirementAnalysisRecord getAnalysis(Long sessionId, int version) {
        var list = jdbc.sql("SELECT * FROM requirement_analysis WHERE session_id = :sid AND version = :ver")
                .param("sid", sessionId).param("ver", version).query(this::map).list();
        if (list.isEmpty()) throw new BusinessException("分析结果不存在");
        return list.get(0);
    }

    String buildAnalysisSystemPrompt() {
        return """
                你是一个资深测试分析师。你的任务是分析用户需求并输出结构化的分析结果。

                分析必须按以下顺序进行：
                1. 先理解需求（理解业务目标、用户角色、核心流程）
                2. 再做风险扫描（识别评审前需确认的问题、异常场景、边界条件）
                3. 再判断哪些必须现在澄清（只有阻断准确分析的问题才进入澄清）
                4. 最后拆测试点（按需求类型选拆解骨架，不要全部混成功能点列表）

                请返回以下 JSON 格式（不要返回其他内容，只返回 JSON）：
                {
                  "analysis": {
                    "requirement_understanding": "对需求的理解总结",
                    "business_domain": "业务领域",
                    "requirement_type": "RULE/FORM/UI/STATE/DATA/MIXED",
                    "input_sources": ["PRD_TEXT/PRD_FILE/BLUEPRINT/PROTO_OR_DESIGN/TOM/VERBAL/UNKNOWN"],
                    "input_source_notes": "对输入源的具体说明",
                    "review_risk_questions": [
                      {
                        "question": "评审前建议确认的问题",
                        "reason": "为什么要问",
                        "impact": "不确认会影响什么",
                        "source_basis": ["引用的 TOM/页面/业务包/轨迹依据"]
                      }
                    ],
                    "risk_scenarios": ["异常/状态/数据/权限/幂等等风险场景"],
                    "boundary_conditions": ["时间/数量/金额/状态/重复操作/并发边界"],
                    "affected_modules": ["受影响模块"],
                    "affected_pages": ["受影响页面"],
                    "affected_fields": ["受影响字段"],
                    "affected_flows": ["受影响流程"],
                    "affected_roles": ["受影响角色"],
                    "conflicts": ["冲突点"],
                    "uncertain_items": ["分析中的不确定项或风险，不等同于反问用户的问题"],
                    "out_of_scope": ["超出范围"]
                  },
                  "test_points": [
                    {
                      "title": "测试点标题",
                      "description": "测试点描述",
                      "test_dimension": "测试维度",
                      "point_type": "MAIN_FLOW/BRANCH/BOUNDARY/EXCEPTION/STATE/DATA/AUTH/CONCURRENCY/IDEMPOTENT",
                      "priority_hint": "CORE/EXTENDED/RISK",
                      "related_module": "关联模块",
                      "related_page": "关联页面",
                      "related_flow": "关联流程",
                      "source_basis": ["必须引用下方 TOM/页面/业务包/轨迹证据中的原文名称"],
                      "source_refs": {
                        "tom_node_refs": ["TOM 节点名称"],
                        "page_refs": ["页面或路由"],
                        "business_pack_refs": ["业务包条目"],
                        "trace_refs": ["轨迹/步骤/摘要依据"]
                      },
                      "coverage_status": "SUPPORTED/PARTIAL/LOW_EVIDENCE",
                      "unsupported_items": ["缺少证据但仍需人工确认的内容"],
                      "confidence": 0.8,
                      "needs_confirmation": true
                    }
                  ],
                  "clarification_questions": [
                    {
                      "question": "反问问题",
                      "reason": "原因",
                      "impact": "影响"
                    }
                  ],
                  "assumptions": [
                    {
                      "assumption": "假设内容",
                      "reason": "假设原因"
                    }
                  ]
                }

                规则：
                1. 不要直接生成测试用例，只做分析和测试点提取
                2. 如果信息不足，必须在 clarification_questions 中提出反问
                3. clarification_questions 是需要用户回答的反问，必须独立返回，不能只放在测试点或右侧补充信息里
                4. uncertain_items 是分析风险或假设，不得用来替代 clarification_questions
                5. review_risk_questions 是评审/分析阶段的风险确认问题，与 clarification_questions 分离输出
                6. 如果某个风险问题足以阻断准确分析，应同时进入 clarification_questions
                7. 测试点只是分析结果，不是最终用例
                8. 必须优先使用"项目证据上下文"中的 TOM、页面画像、业务包、轨迹摘要作为 source_basis
                9. 没有证据支持的内容必须标记 needs_confirmation=true 和 coverage_status=LOW_EVIDENCE，不允许伪装成已确认事实
                10. 测试点之间不要混合多个业务目标；每个测试点只描述一个可验证目标
                11. 原型/页面上已有控件，不等于本次新增测试范围
                12. 测试点要按 requirement_type 选拆解骨架：
                    - RULE 型重点覆盖条件组合、边界值、互斥规则
                    - FORM 型重点覆盖字段校验、必填、联动、提交
                    - UI 型重点覆盖交互流程、状态切换、响应式
                    - STATE 型重点覆盖状态流转、异常恢复、并发
                    - DATA 型重点覆盖一致性、并发、幂等、数据量
                    - MIXED 型按涉及的主要类型组合拆解
                13. 必须识别 input_sources（输入源类型），至少区分：
                    - PRD_TEXT：纯文本需求描述
                    - PRD_FILE：PRD 文件附件
                    - BLUEPRINT：蓝湖/原型/设计稿链接或截图
                    - PROTO_OR_DESIGN：高保真原型或 UI 设计稿
                    - TOM：仅来自 TOM 模型
                    - VERBAL：口头描述/会议纪要
                    - UNKNOWN：无法判断
                14. 当 input_sources 包含 BLUEPRINT 或 PROTO_OR_DESIGN 时，必须在 review_risk_questions 中追加：
                    "原型/设计稿控件存在不等于本次新增测试范围，需确认具体需求变更点"
                15. review_risk_questions 中的问题必须标注 source_basis（引用 TOM/页面/业务包/轨迹依据），不允许无依据的风险问题
                """;
    }

    private String buildAnalysisUserPrompt(String requirementText, String tomSnapshot,
                                           ProjectSemanticContextService.BuildResult semanticContext,
                                           String previousAnswers) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 需求描述\n").append(clipForPrompt(requirementText, MAX_ANALYSIS_REQUIREMENT_CHARS));
        if (tomSnapshot != null && !tomSnapshot.isBlank()) {
            sb.append("\n\n## TOM 上下文\n").append(clipForPrompt(tomSnapshot, MAX_ANALYSIS_TOM_CHARS));
        }
        if (semanticContext != null && semanticContext.promptSection() != null
                && !semanticContext.promptSection().isBlank()) {
            sb.append("\n\n## 项目证据上下文（必须引用，不能编造）")
                    .append(semanticContext.promptSection());
            sb.append("\n## 项目证据结构化摘要\n")
                    .append(toJson(buildEvidenceSummary(semanticContext)));
        }
        if (previousAnswers != null && !previousAnswers.isBlank()) {
            sb.append("\n\n## 用户补充/修改\n").append(clipForPrompt(previousAnswers, MAX_PREVIOUS_ANSWERS_CHARS));
        }
        sb.append("\n\n请分析以上需求，输出结构化的分析结果。测试点必须写明 source_basis/source_refs；没有证据的内容只放入待确认或假设。");
        return sb.toString();
    }

    static String buildGenerationRequirementText(RequirementAnalysisRecord analysis) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "原始需求", analysis.requirementText(), MAX_GENERATION_REQUIREMENT_CHARS);
        appendSection(sb, "已确认/最新需求分析", analysis.analysisResult(), MAX_GENERATION_ANALYSIS_CHARS);
        appendSection(sb, "测试点", analysis.testPoints(), MAX_GENERATION_TEST_POINTS_CHARS);
        appendSection(sb, "TOM/项目证据快照", analysis.tomScopeSnapshot(), MAX_GENERATION_TOM_CHARS);
        appendSection(sb, "澄清答案", analysis.clarificationAnswers(), MAX_GENERATION_SMALL_SECTION_CHARS);
        appendSection(sb, "生成假设", analysis.assumptions(), MAX_GENERATION_SMALL_SECTION_CHARS);
        sb.append("""

                ## 用例生成约束
                1. 用例必须围绕上述需求分析和测试点展开。
                2. 每条测试点至少生成一条可执行用例，存在边界/异常/权限/状态流转时补充覆盖。
                3. 不要引入分析结果之外的固定样例数据；缺失信息只能写入前置条件或假设说明。
                4. 用例标题、步骤、预期结果必须互相对应，避免只像测试数据列表。
                5. 每条用例必须能追溯到一个测试点；不能把多个测试点混成一条用例。
                6. 步骤、页面、字段、角色、流程优先来自 TOM/项目证据快照；没有证据的内容必须写成“待确认/假设”，不要当作事实。
                7. 如果测试点标记 LOW_EVIDENCE，只能生成低置信草稿，不能扩展成额外业务场景。
                """);
        return sb.toString().trim();
    }

    private static void appendSection(StringBuilder sb, String title, String content) {
        appendSection(sb, title, content, Integer.MAX_VALUE);
    }

    private static void appendSection(StringBuilder sb, String title, String content, int maxChars) {
        if (content == null || content.isBlank() || "[]".equals(content) || "{}".equals(content)) {
            return;
        }
        sb.append("## ").append(title).append("\n").append(clipForPrompt(content, maxChars)).append("\n\n");
    }

    static String clipForPrompt(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (maxChars <= 0 || trimmed.length() <= maxChars) {
            return trimmed;
        }
        int headChars = Math.max(1, (int) (maxChars * 0.7));
        int tailChars = Math.max(1, maxChars - headChars);
        String omitted = "\n\n...[中间内容已截断，保留首尾关键信息；完整内容仍保存在会话/资产中]...\n\n";
        return trimmed.substring(0, headChars) + omitted + trimmed.substring(trimmed.length() - tailChars);
    }

    private String buildGenerationPromptSnapshot(String basePromptSnapshot, RequirementAnalysisRecord analysis) {
        StringBuilder sb = new StringBuilder();
        if (basePromptSnapshot != null && !basePromptSnapshot.isBlank()) {
            sb.append(basePromptSnapshot.trim()).append("\n\n");
        }
        sb.append("## 生成来源\n")
                .append("requirement_analysis_id: ").append(analysis.id()).append("\n")
                .append("requirement_analysis_version: ").append(analysis.version()).append("\n")
                .append("rule: 生成结果必须与该分析版本中的需求理解、测试点和澄清答案保持一致。");
        return sb.toString();
    }

    private Map<String, Object> buildDraftSourceRefs(Long taskId, Long sessionId, RequirementAnalysisRecord analysis,
                                                     DirectCaseGenerationService.GenerateResult result) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("source", "REQUIREMENT_ANALYSIS");
        refs.put("sourceType", "TOM_BOUND_GENERATION");
        refs.put("taskId", taskId);
        refs.put("sessionId", sessionId);
        refs.put("analysisId", analysis.id());
        refs.put("analysisVersion", analysis.version());
        refs.put("evidenceSummary", extractEvidenceSummary(analysis.analysisResult()));
        refs.put("sourceTestPoints", extractSourceTestPoints(analysis.testPoints()));
        refs.put("qualityGate", buildGenerationQualityGate(analysis, result));
        return refs;
    }

    private String buildDraftSourceRefsJson(Long taskId, Long sessionId, RequirementAnalysisRecord analysis,
                                            DirectCaseGenerationService.GenerateResult result) {
        Map<String, Object> refs = buildDraftSourceRefs(taskId, sessionId, analysis, result);
        String json = toJson(refs);
        return json == null ? "{}" : json;
    }

    private void applyDraftEvidenceLinks(Long taskId, Long sessionId, RequirementAnalysisRecord analysis,
                                         DirectCaseGenerationService.GenerateResult result) {
        List<DraftSourceRow> rows = jdbc.sql("""
                SELECT id, case_title, module_name, steps, expected_result, source_refs_json
                FROM test_case_draft
                WHERE task_id = :taskId
                ORDER BY id ASC
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new DraftSourceRow(
                        rs.getLong("id"),
                        rs.getString("case_title"),
                        rs.getString("module_name"),
                        rs.getString("steps"),
                        rs.getString("expected_result"),
                        rs.getString("source_refs_json")
                ))
                .list();
        for (DraftSourceRow row : rows) {
            Map<String, Object> mergedRefs = buildDraftSourceRefs(taskId, sessionId, analysis, result);
            Map<String, Object> generatorRefs = readJsonObject(row.sourceRefsJson());
            if (generatorRefs != null && !generatorRefs.isEmpty()) {
                mergedRefs.put("generatorRefs", generatorRefs);
                copyIfPresent(generatorRefs, mergedRefs, "sourceTestPoint");
                copyIfPresent(generatorRefs, mergedRefs, "sourceBasis");
                copyIfPresent(generatorRefs, mergedRefs, "unsupportedItems");
                copyIfPresent(generatorRefs, mergedRefs, "confidence");
            }
            Map<String, Object> qualityGate = buildDraftQualityGate(analysis, result, generatorRefs, row);
            mergedRefs.put("qualityGate", qualityGate);
            jdbcTemplate.update("""
                    UPDATE test_case_draft
                    SET session_id = ?, analysis_id = ?, analysis_version = ?, source_refs_json = ?, quality_status = ?
                    WHERE id = ?
                    """, sessionId, analysis.id(), analysis.version(), toJson(mergedRefs),
                    String.valueOf(qualityGate.get("status")), row.id());
        }
    }

    private ProjectSemanticContextService.BuildResult buildSemanticEvidence(Long projectId, String requirementText) {
        try {
            return semanticContextService.build(projectId, requirementText, List.of(), 14);
        } catch (Exception e) {
            log.warn("Project semantic evidence build failed: {}", e.getMessage());
            return new ProjectSemanticContextService.BuildResult("", List.of());
        }
    }

    private String enrichAnalysisResult(String analysisResult,
                                        ProjectSemanticContextService.BuildResult semanticContext,
                                        String clarificationQuestions) {
        Map<String, Object> root = readJsonObject(analysisResult);
        if (root == null) {
            root = new LinkedHashMap<>();
        }
        root.put("evidence_summary", buildEvidenceSummary(semanticContext));
        List<String> uncertainItems = new ArrayList<>(toStringList(root.get("uncertain_items")));
        if (uncertainItems.isEmpty() && (semanticContext == null || semanticContext.signals().isEmpty())) {
            uncertainItems.add("当前需求未命中项目 TOM、页面画像、业务包或轨迹证据，生成结果需要人工确认。");
        }
        List<Map<String, Object>> questions = readJsonObjectList(clarificationQuestions);
        if (questions != null && !questions.isEmpty()) {
            root.put("clarification_questions", questions);
        }
        root.put("uncertain_items", uncertainItems);

        // 兜底补全新字段，避免前端解析失败
        // 如果 analysis 子对象已有这些字段，复制到根级别
        @SuppressWarnings("unchecked")
        Map<String, Object> analysisMap = root.get("analysis") instanceof Map ? (Map<String, Object>) root.get("analysis") : null;
        root.putIfAbsent("requirement_type",
                analysisMap != null && analysisMap.containsKey("requirement_type") ? analysisMap.get("requirement_type") : "MIXED");
        root.putIfAbsent("input_sources",
                analysisMap != null && analysisMap.containsKey("input_sources") ? analysisMap.get("input_sources") : List.of("UNKNOWN"));
        root.putIfAbsent("review_risk_questions",
                analysisMap != null && analysisMap.containsKey("review_risk_questions") ? analysisMap.get("review_risk_questions") : List.of());
        root.putIfAbsent("risk_scenarios",
                analysisMap != null && analysisMap.containsKey("risk_scenarios") ? analysisMap.get("risk_scenarios") : List.of());
        root.putIfAbsent("boundary_conditions",
                analysisMap != null && analysisMap.containsKey("boundary_conditions") ? analysisMap.get("boundary_conditions") : List.of());

        String json = toJson(root);
        return json == null ? analysisResult : json;
    }

    private String enrichTestPoints(String testPoints, ProjectSemanticContextService.BuildResult semanticContext) {
        List<Map<String, Object>> rows = readJsonObjectList(testPoints);
        if (rows == null || rows.isEmpty()) {
            return testPoints;
        }
        Map<String, Object> evidenceSummary = buildEvidenceSummary(semanticContext);
        @SuppressWarnings("unchecked")
        List<String> evidenceBasis = (List<String>) evidenceSummary.getOrDefault("source_basis", List.of());
        @SuppressWarnings("unchecked")
        List<String> unsupportedItems = (List<String>) evidenceSummary.getOrDefault("unsupported_items", List.of());
        String coverageStatus = unsupportedItems.isEmpty() ? "SUPPORTED" : "LOW_EVIDENCE";

        for (Map<String, Object> row : rows) {
            if (!hasNonEmptyArray(row.get("source_basis"))) {
                row.put("source_basis", evidenceBasis);
            }
            if (!row.containsKey("source_refs")) {
                row.put("source_refs", Map.of(
                        "tom_node_refs", evidenceSummary.getOrDefault("tom_node_refs", List.of()),
                        "page_refs", evidenceSummary.getOrDefault("page_refs", List.of()),
                        "business_pack_refs", evidenceSummary.getOrDefault("business_pack_refs", List.of()),
                        "trace_refs", evidenceSummary.getOrDefault("trace_refs", List.of())
                ));
            }
            row.putIfAbsent("coverage_status", coverageStatus);
            row.putIfAbsent("point_type", "MAIN_FLOW");
            row.putIfAbsent("priority_hint", "CORE");
            if (!unsupportedItems.isEmpty()) {
                row.putIfAbsent("unsupported_items", unsupportedItems);
                row.put("needs_confirmation", true);
            }
        }
        String json = toJson(rows);
        return json == null ? testPoints : json;
    }

    private Map<String, Object> buildEvidenceSummary(ProjectSemanticContextService.BuildResult semanticContext) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<ProjectSemanticContextService.SemanticSignal> signals =
                semanticContext == null || semanticContext.signals() == null ? List.of() : semanticContext.signals();
        List<Map<String, Object>> signalRefs = signals.stream()
                .limit(14)
                .map(signal -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("category", signal.category());
                    item.put("title", signal.title());
                    item.put("summary", signal.summary());
                    item.put("routeHint", signal.routeHint());
                    return item;
                })
                .toList();
        List<String> tomRefs = titlesByCategory(signals, "TOM");
        List<String> pageRefs = titlesByCategory(signals, "页面画像");
        List<String> businessPackRefs = signals.stream()
                .filter(signal -> signal.category() != null && signal.category().startsWith("业务包:"))
                .map(ProjectSemanticContextService.SemanticSignal::title)
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .distinct()
                .limit(8)
                .toList();
        List<String> traceRefs = signals.stream()
                .filter(signal -> "步骤模板".equals(signal.category()) || "业务摘要".equals(signal.category()))
                .map(signal -> firstNonBlank(signal.title(), signal.summary()))
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .distinct()
                .limit(8)
                .toList();
        List<String> basis = signals.stream()
                .map(signal -> signal.category() + ":" + signal.title())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(10)
                .toList();

        summary.put("evidence_count", signals.size());
        summary.put("confidence_label", signals.isEmpty() ? "LOW" : (signals.size() >= 4 ? "HIGH" : "MEDIUM"));
        summary.put("tom_node_refs", tomRefs);
        summary.put("page_refs", pageRefs);
        summary.put("business_pack_refs", businessPackRefs);
        summary.put("trace_refs", traceRefs);
        summary.put("source_basis", basis);
        summary.put("signals", signalRefs);
        summary.put("unsupported_items", signals.isEmpty()
                ? List.of("未命中项目 TOM、页面画像、业务包或轨迹证据")
                : List.of());
        return summary;
    }

    private List<String> titlesByCategory(List<ProjectSemanticContextService.SemanticSignal> signals, String category) {
        return signals.stream()
                .filter(signal -> signal.category() != null
                        && (category.equals(signal.category()) || signal.category().startsWith(category + ":")))
                .map(ProjectSemanticContextService.SemanticSignal::title)
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private Map<String, Object> extractEvidenceSummary(String analysisResult) {
        Map<String, Object> root = readJsonObject(analysisResult);
        Object summary = root == null ? null : root.get("evidence_summary");
        if (summary instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return Map.of();
    }

    private List<Map<String, Object>> extractSourceTestPoints(String testPoints) {
        List<Map<String, Object>> rows = readJsonObjectList(testPoints);
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", row.get("title"));
            item.put("source_basis", row.getOrDefault("source_basis", List.of()));
            item.put("source_refs", row.getOrDefault("source_refs", Map.of()));
            item.put("coverage_status", row.getOrDefault("coverage_status", "UNKNOWN"));
            item.put("needs_confirmation", row.getOrDefault("needs_confirmation", false));
            return item;
        }).toList();
    }

    private Map<String, Object> buildGenerationQualityGate(RequirementAnalysisRecord analysis,
                                                           DirectCaseGenerationService.GenerateResult result) {
        Map<String, Object> gate = new LinkedHashMap<>();
        int testPointCount = countJsonArray(analysis.testPoints());
        int draftCount = result == null || result.drafts() == null ? 0 : result.drafts().size();
        Map<String, Object> evidenceSummary = extractEvidenceSummary(analysis.analysisResult());
        int evidenceCount = numberValue(evidenceSummary.get("evidence_count"));
        List<String> warnings = new ArrayList<>();
        if (evidenceCount == 0) {
            warnings.add("未命中项目证据，草稿为低置信生成，需要人工确认。");
        }
        if (testPointCount > 0 && draftCount < testPointCount) {
            warnings.add("草稿数量少于测试点数量，可能存在覆盖缺口。");
        }
        boolean hasLowEvidencePoint = extractSourceTestPoints(analysis.testPoints()).stream()
                .anyMatch(tp -> "LOW_EVIDENCE".equals(String.valueOf(tp.get("coverage_status"))));
        if (hasLowEvidencePoint) {
            warnings.add("存在低证据测试点，对应草稿不能直接视为已确认业务规则。");
        }

        String status;
        if (evidenceCount == 0) {
            status = "LOW_EVIDENCE";
        } else if (!warnings.isEmpty()) {
            status = "PARTIAL";
        } else {
            status = "PASS";
        }
        gate.put("status", status);
        gate.put("testPointCount", testPointCount);
        gate.put("draftCount", draftCount);
        gate.put("evidenceCount", evidenceCount);
        gate.put("warnings", warnings);
        return gate;
    }

    private Map<String, Object> buildDraftQualityGate(RequirementAnalysisRecord analysis,
                                                      DirectCaseGenerationService.GenerateResult result,
                                                      Map<String, Object> generatorRefs,
                                                      DraftSourceRow draft) {
        Map<String, Object> sessionGate = buildGenerationQualityGate(analysis, result);
        Map<String, Object> gate = new LinkedHashMap<>(sessionGate);
        List<String> warnings = new ArrayList<>();
        Object existingWarnings = sessionGate.get("warnings");
        if (existingWarnings instanceof Collection<?> collection) {
            collection.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .forEach(warnings::add);
        }

        String sourceTestPoint = generatorRefs == null ? "" : String.valueOf(generatorRefs.getOrDefault("sourceTestPoint", "")).trim();
        List<String> sourceBasis = generatorRefs == null ? List.of() : toStringList(generatorRefs.get("sourceBasis"));
        List<String> unsupportedItems = generatorRefs == null ? List.of() : toStringList(generatorRefs.get("unsupportedItems"));
        double confidence = generatorRefs == null ? 0.0 : doubleValue(generatorRefs.get("confidence"), 0.0);
        List<String> testPointTitles = extractSourceTestPoints(analysis.testPoints()).stream()
                .map(row -> String.valueOf(row.getOrDefault("title", "")).trim())
                .filter(title -> !title.isBlank())
                .toList();
        List<String> evidenceBasis = toStringList(extractEvidenceSummary(analysis.analysisResult()).get("source_basis"));
        int stepCount = countActionLines(draft == null ? null : draft.steps());
        int expectedCount = countActionLines(draft == null ? null : draft.expectedResult());
        boolean invalidStructure = false;

        if (sourceTestPoint.isBlank()) {
            warnings.add("该用例未声明来源测试点，需人工确认是否覆盖正确。");
            invalidStructure = true;
        } else if (!testPointTitles.isEmpty() && testPointTitles.stream().noneMatch(title -> fuzzyContains(sourceTestPoint, title))) {
            warnings.add("该用例声明的来源测试点未命中当前分析测试点。");
        }
        if (sourceBasis.isEmpty()) {
            warnings.add("该用例未声明生成依据，需补充 TOM/页面/业务包/轨迹证据。");
            invalidStructure = true;
        } else if (!evidenceBasis.isEmpty() && sourceBasis.stream().noneMatch(basis ->
                evidenceBasis.stream().anyMatch(evidence -> fuzzyContains(basis, evidence)))) {
            warnings.add("该用例生成依据未命中当前项目证据摘要。");
        }
        if (!unsupportedItems.isEmpty()) {
            warnings.add("该用例包含未证实假设：" + String.join("；", unsupportedItems));
        }
        if (confidence > 0.0 && confidence < 0.65) {
            warnings.add("该用例模型置信度低于 0.65。");
        }
        if (draft == null || isBlank(draft.steps()) || isBlank(draft.expectedResult())) {
            warnings.add("该用例步骤或预期为空，不能直接作为有效草稿。");
            invalidStructure = true;
        } else if (stepCount > 0 && expectedCount > 0 && stepCount != expectedCount) {
            warnings.add("该用例步骤数与预期结果数不一致。");
        }
        if (draft != null && (containsAny(draft.caseTitle(), "模型返回原文", "待人工整理")
                || containsAny(draft.moduleName(), "默认模块"))) {
            warnings.add("该用例仍带有模型兜底字段，需要人工整理。");
            invalidStructure = true;
        }

        String status;
        if (generatorRefs == null || invalidStructure || sourceBasis.isEmpty() || !unsupportedItems.isEmpty()
                || "LOW_EVIDENCE".equals(sessionGate.get("status"))) {
            status = "LOW_EVIDENCE";
        } else if (!warnings.isEmpty() || "PARTIAL".equals(sessionGate.get("status"))) {
            status = "PARTIAL";
        } else {
            status = "PASS";
        }
        gate.put("status", status);
        gate.put("sourceTestPoint", sourceTestPoint);
        gate.put("sourceBasisCount", sourceBasis.size());
        gate.put("unsupportedCount", unsupportedItems.size());
        gate.put("confidence", confidence);
        gate.put("stepCount", stepCount);
        gate.put("expectedCount", expectedCount);
        gate.put("warnings", warnings.stream().distinct().toList());
        return gate;
    }

    private String buildGenerationQualityStatus(RequirementAnalysisRecord analysis,
                                                DirectCaseGenerationService.GenerateResult result) {
        return String.valueOf(buildGenerationQualityGate(analysis, result).get("status"));
    }

    private Map<String, Object> readJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> readJsonObjectList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasNonEmptyArray(Object value) {
        return value instanceof Collection<?> collection && !collection.isEmpty();
    }

    private int countJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            var node = objectMapper.readTree(json);
            return node.isArray() ? node.size() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private List<String> toStringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList();
        }
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? List.of() : List.of(text);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private int countActionLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*\\d+[\\.、)]\\s*\\S+")
                .matcher(text);
        int numbered = 0;
        while (matcher.find()) {
            numbered++;
        }
        if (numbered > 0) {
            return numbered;
        }
        return (int) Arrays.stream(text.split("\\R+"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .count();
    }

    private boolean fuzzyContains(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = normalizeForCompare(left);
        String b = normalizeForCompare(right);
        return !a.isBlank() && !b.isBlank() && (a.contains(b) || b.contains(a));
    }

    private String normalizeForCompare(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、“”‘’（）【】《》]+", "");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return Arrays.stream(needles).anyMatch(value::contains);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String extractJson(String llmOutput, String key) {
        if (llmOutput == null) return null;
        for (String candidate : jsonCandidates(llmOutput)) {
            String extracted = extractFromParsedRoot(candidate, key);
            if (extracted != null) return extracted;
        }

        // Last-resort fallback: scan from the raw text, but only return validated JSON fragments.
        return extractJsonFragment(llmOutput, key);
    }

    static String extractPlainText(String llmOutput, String key) {
        if (llmOutput == null) return null;
        for (String candidate : jsonCandidates(llmOutput)) {
            try {
                var root = objectMapper.readTree(candidate);
                var node = root.get(key);
                if (node != null && node.isTextual()) return node.asText();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static List<String> jsonCandidates(String llmOutput) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String trimmed = llmOutput.trim();
        if (!trimmed.isBlank()) {
            candidates.add(trimmed);
        }

        int fenceStart = trimmed.indexOf("```");
        while (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            if (contentStart < 0) break;
            int fenceEnd = trimmed.indexOf("```", contentStart + 1);
            if (fenceEnd < 0) break;
            String fenced = trimmed.substring(contentStart + 1, fenceEnd).trim();
            if (!fenced.isBlank()) {
                candidates.add(fenced);
            }
            fenceStart = trimmed.indexOf("```", fenceEnd + 3);
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            candidates.add(trimmed.substring(firstBrace, lastBrace + 1));
        }
        return new ArrayList<>(candidates);
    }

    private static String extractFromParsedRoot(String candidate, String key) {
        try {
            var root = objectMapper.readTree(candidate);
            var node = root.get(key);
            if (node != null) return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ignored) {
            // ignore invalid candidate and continue
        }
        return null;
    }

    private static String extractJsonFragment(String llmOutput, String key) {
        int start = llmOutput.indexOf("\"" + key + "\"");
        if (start < 0) return null;
        int colon = llmOutput.indexOf(':', start);
        if (colon < 0) return null;
        int valueStart = colon + 1;
        while (valueStart < llmOutput.length() && Character.isWhitespace(llmOutput.charAt(valueStart))) valueStart++;
        if (valueStart >= llmOutput.length()) return null;
        char open = llmOutput.charAt(valueStart);
        if (open == '{' || open == '[') {
            String candidate = readBalancedJson(llmOutput, valueStart, open);
            return validateJson(candidate);
        }

        if (open == '"') {
            String candidate = readQuotedJsonString(llmOutput, valueStart);
            return validateJson(candidate);
        }

        int end = valueStart;
        while (end < llmOutput.length()) {
            char c = llmOutput.charAt(end);
            if (c == ',' || c == '\n' || c == '\r' || c == '}' || c == ']') {
                break;
            }
            end++;
        }
        String primitive = llmOutput.substring(valueStart, end).trim();
        return validateJson(primitive.isEmpty() ? null : primitive);
    }

    private static String readBalancedJson(String text, int valueStart, char open) {
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int pos = valueStart; pos < text.length(); pos++) {
            char c = text.charAt(pos);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(valueStart, pos + 1);
                }
            }
        }
        return null;
    }

    private static String readQuotedJsonString(String text, int valueStart) {
        boolean escaping = false;
        for (int pos = valueStart + 1; pos < text.length(); pos++) {
            char c = text.charAt(pos);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '"') {
                return text.substring(valueStart, pos + 1);
            }
        }
        return null;
    }

    private static String validateJson(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(candidate));
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    static String normalizeJsonColumn(String candidate) {
        return validateJson(candidate);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON serialization failed, storing null: {}", e.getMessage());
            return null;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private RequirementAnalysisRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new RequirementAnalysisRecord(
                rs.getLong("id"), rs.getLong("session_id"), rs.getInt("version"),
                rs.getInt("sub_version"),
                rs.getString("requirement_text"), rs.getString("analysis_result"),
                rs.getString("tom_scope_snapshot"), rs.getString("clarification_questions"),
                rs.getString("clarification_answers"), rs.getString("assumptions"),
                rs.getString("test_points"),
                rs.getString("affected_cases"), rs.getString("change_scope"),
                rs.getString("new_cases_needed"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private record DraftSourceRow(Long id, String caseTitle, String moduleName, String steps,
                                  String expectedResult, String sourceRefsJson) {
    }
}
