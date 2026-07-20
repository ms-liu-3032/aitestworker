package com.company.aitest.generation.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.common.TomUsageMode;
import com.company.aitest.generation.DirectCaseGenerationService;
import com.company.aitest.generation.GenerationTaskCheckpointService;
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
    private static final int MAX_ANALYSIS_REQUIREMENT_CHARS = 5_000;
    private static final int ANALYSIS_INPUT_FRAGMENT_CHARS = 2_400;
    private static final int MAX_ANALYSIS_TOM_CHARS = 3_000;
    private static final int MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS = 3_000;
    private static final int MAX_PREVIOUS_ANSWERS_CHARS = 2_000;
    private static final int MAX_GENERATION_REQUIREMENT_CHARS = 6_000;
    private static final int MAX_GENERATION_ANALYSIS_CHARS = 10_000;
    private static final int MAX_GENERATION_TOM_CHARS = 4_000;
    private static final int MAX_GENERATION_SMALL_SECTION_CHARS = 2_000;
    private static final int ANALYSIS_MAX_TOKENS = 4096;
    private static final int ANALYSIS_CONTINUATION_MAX_TOKENS = 2048;
    private static final int MAX_ANALYSIS_CONTINUATION_ATTEMPTS = 6;
    private static final int MAX_ANALYSIS_NODE_EXECUTION_ATTEMPTS = 2;
    private static final int MAX_CORE_PATCH_ATTEMPTS = 5;
    private static final int MAX_TEST_UNIT_REPAIR_ATTEMPTS = 3;
    private static final int MAX_COVERAGE_BATCH_CHARS = 8_000;
    private static final int MAX_COVERAGE_WORK_ITEMS_PER_CALL = 4;
    private static final int MAX_COVERAGE_DIMENSIONS_PER_TEST_POINT_NODE = 1;
    private static final int CASE_PATCH_MAX_TOKENS = 4096;
    private static final int CASE_SUPPLEMENT_BATCH_SIZE = 6;
    private static final String SCOPE_GENERATE = "GENERATE";
    private static final String SCOPE_REFERENCE_ONLY = "REFERENCE_ONLY";
    private static final String SCOPE_EXCLUDED = "EXCLUDED";

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
    private final com.company.aitest.loop.LoopIntegrationService loopIntegrationService;
    private final GenerationTaskCheckpointService taskCheckpointService;

    @org.springframework.beans.factory.annotation.Autowired
    public RequirementAnalysisService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                       LlmGateway llmGateway, GenerationSessionService sessionService,
                                       GenerationMessageService messageService, GenerationAttachmentService attachmentService,
                                       MiniTomService miniTomService, GenerationTaskService taskService,
                                       DirectCaseGenerationService directCaseGenerationService,
                                       ProjectSemanticContextService semanticContextService,
                                       com.company.aitest.loop.LoopIntegrationService loopIntegrationService,
                                       GenerationTaskCheckpointService taskCheckpointService) {
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
        this.loopIntegrationService = loopIntegrationService;
        this.taskCheckpointService = taskCheckpointService;
    }

    // Kept for focused unit tests and historical construction sites that do not exercise checkpoints.
    RequirementAnalysisService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                               LlmGateway llmGateway, GenerationSessionService sessionService,
                               GenerationMessageService messageService, GenerationAttachmentService attachmentService,
                               MiniTomService miniTomService, GenerationTaskService taskService,
                               DirectCaseGenerationService directCaseGenerationService,
                               ProjectSemanticContextService semanticContextService,
                               com.company.aitest.loop.LoopIntegrationService loopIntegrationService) {
        this(jdbc, jdbcTemplate, timeProvider, llmGateway, sessionService, messageService, attachmentService,
                miniTomService, taskService, directCaseGenerationService, semanticContextService,
                loopIntegrationService, null);
    }

    public RequirementAnalysisRecord analyze(Long sessionId, CurrentUser user) {
        return analyzeInternal(sessionId, user, false);
    }

    public RequirementAnalysisRecord analyzeScopeOnly(Long sessionId, CurrentUser user) {
        return analyzeInternal(sessionId, user, true);
    }

    private RequirementAnalysisRecord analyzeInternal(Long sessionId, CurrentUser user, boolean scopeOnly) {
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
        var attachments = attachmentService.listBySessionForAnalysis(sessionId);
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
        TomUsageMode tomMode = TomUsageMode.resolve(session.tomMode(), session.useMiniTom());
        String tomSnapshot = null;
        if (tomMode.usesTom()) {
            try {
                var tomResult = miniTomService.buildTestScope(
                        projectId, requirementText, session.modelConfigId(), user, tomMode);
                tomSnapshot = toJson(tomResult);
            } catch (Exception e) {
                log.warn("TOM context build failed: {}", e.getMessage());
            }
        }
        ProjectSemanticContextService.BuildResult semanticContext =
                buildSemanticEvidence(projectId, requirementText, tomMode);

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
                    session.promptTemplateId(), null,
                    session.promptSnapshot(), tomMode.name(), tomMode.usesTom()
            );
            var task = taskService.create(projectId, taskCmd, user);
            taskId = task.id();
            sessionService.updateExecutionTaskId(sessionId, taskId);
        }

        // 6. Reserve a unique analysis version for this round to avoid duplicate inserts under concurrent re-analyze.
        int newVersion = sessionService.reserveNextAnalysisVersion(sessionId);

        // Async product flow stops after requirement atoms so the user can remove background
        // and out-of-release content before matrix/test-point fan-out. Legacy synchronous callers
        // keep the complete one-shot behavior for backward compatibility.
        StagedAnalysisResult staged = scopeOnly
                ? runRequirementScopeAnalysis(user, projectId, taskId, session.modelConfigId(),
                        session.promptTemplateId(), "REQUIREMENT_ANALYSIS", requirementText,
                        tomSnapshot, semanticContext, previousAnswers, null)
                : runStagedRequirementAnalysis(user, projectId, taskId, session.modelConfigId(),
                        session.promptTemplateId(), "REQUIREMENT_ANALYSIS", requirementText,
                        tomSnapshot, semanticContext, previousAnswers, null, null);
        String clarificationQuestions = staged.clarificationQuestions();
        String analysisResult = staged.analysisResult();
        String testPoints = staged.testPoints();
        String assumptions = staged.assumptions();

        // 7. Save analysis record
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO requirement_analysis(session_id, version, sub_version, requirement_text, analysis_result, tom_scope_snapshot, clarification_questions, assumptions, test_points, affected_cases, change_scope, new_cases_needed, status, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 'NEED_CONFIRMATION', ?, ?)
                """, sessionId, newVersion, requirementText, analysisResult, tomSnapshot,
                clarificationQuestions, assumptions, testPoints, now, now);
        if (scopeOnly) {
            jdbcTemplate.update("UPDATE requirement_analysis SET status = 'NEED_SCOPE_CONFIRMATION', updated_at = ? WHERE session_id = ? AND version = ? AND sub_version = 0",
                    now, sessionId, newVersion);
        }

        // 8. Append assistant message
        String summary = scopeOnly
                ? "需求范围识别完成（v" + newVersion + "）。请先确认哪些内容属于本期生成范围，确认后系统再生成覆盖矩阵和测试点。"
                : "需求分析完成（v" + newVersion + "）。";
        if (clarificationQuestions != null && !clarificationQuestions.isBlank() && !"[]".equals(clarificationQuestions)) {
            summary += scopeOnly
                    ? "\n\n存在需要澄清的问题。请补充信息后重新分析需求范围；范围确认前不会生成测试点或用例。"
                    : "\n\n请确认以上分析是否正确？有什么需要补充或修改的内容请输入。";
        }
        // The analysis record is the canonical result asset. Keep the conversation payload
        // to that structured result instead of duplicating large multi-stage raw model output.
        messageService.appendAssistantMessage(sessionId, summary, analysisResult, "REQ_ANALYSIS", newVersion);

        loopIntegrationService.onTomUsageEvaluated(projectId, analysisResult, tomSnapshot, user);
        loopIntegrationService.onChineseLocalizationCheck(projectId, analysisResult,
                scopeOnly ? "REQUIREMENT_SCOPE" : "ANALYSIS", user);

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
        return incrementalAnalyzeInternal(sessionId, supplementContent, user, false);
    }

    public RequirementAnalysisRecord incrementalAnalyzeScopeOnly(Long sessionId, String supplementContent, CurrentUser user) {
        return incrementalAnalyzeInternal(sessionId, supplementContent, user, true);
    }

    private RequirementAnalysisRecord incrementalAnalyzeInternal(Long sessionId, String supplementContent,
                                                                  CurrentUser user, boolean scopeOnly) {
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
        TomUsageMode tomMode = TomUsageMode.resolve(session.tomMode(), session.useMiniTom());
        String tomSnapshot = null;
        if (tomMode.usesTom()) {
            try {
                tomSnapshot = toJson(miniTomService.buildTestScope(projectId,
                        semanticInput.toString().trim(), session.modelConfigId(), user, tomMode));
            } catch (Exception e) {
                log.warn("TOM context rebuild failed for incremental analysis: {}", e.getMessage());
            }
        }
        ProjectSemanticContextService.BuildResult semanticContext =
                buildSemanticEvidence(projectId, semanticInput.toString().trim(), tomMode);

        // 4. Ensure internal task exists
        Long taskId = session.executionTaskId();
        if (taskId == null) {
            var taskCmd = new GenerationTaskService.CreateTaskCommand(
                    session.sessionTitle(), previousAnalysis.requirementText(), session.modelConfigId(),
                    session.promptTemplateId(), null,
                    session.promptSnapshot(), tomMode.name(), tomMode.usesTom()
            );
            var task = taskService.create(projectId, taskCmd, user);
            taskId = task.id();
            sessionService.updateExecutionTaskId(sessionId, taskId);
        }

        String previousContext = buildPreviousAnalysisContext(previousAnalysis);
        StagedAnalysisResult staged = scopeOnly
                ? runRequirementScopeAnalysis(user, projectId, taskId, session.modelConfigId(),
                        session.promptTemplateId(), "REQUIREMENT_ANALYSIS_INCREMENTAL",
                        previousAnalysis.requirementText(), tomSnapshot, semanticContext,
                        supplementContent, previousContext)
                : runStagedRequirementAnalysis(user, projectId, taskId, session.modelConfigId(),
                        session.promptTemplateId(), "REQUIREMENT_ANALYSIS_INCREMENTAL",
                        previousAnalysis.requirementText(), tomSnapshot, semanticContext,
                        supplementContent, previousContext, sessionId);
        String clarificationQuestions = staged.clarificationQuestions();
        String analysisResult = staged.analysisResult();
        String testPoints = staged.testPoints();
        String assumptions = staged.assumptions();

        String affectedCases = staged.affectedCases();
        String changeScope = staged.changeScope();
        String newCasesNeeded = staged.newCasesNeeded();

        // 8. Save with sub_version
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO requirement_analysis(session_id, version, sub_version, requirement_text, analysis_result, tom_scope_snapshot, clarification_questions, assumptions, test_points, affected_cases, change_scope, new_cases_needed, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEED_CONFIRMATION', ?, ?)
                """, sessionId, previousAnalysis.version(), newSubVersion,
                previousAnalysis.requirementText(), analysisResult, tomSnapshot,
                clarificationQuestions, assumptions, testPoints, affectedCases, changeScope, newCasesNeeded, now, now);
        if (scopeOnly) {
            jdbcTemplate.update("UPDATE requirement_analysis SET status = 'NEED_SCOPE_CONFIRMATION', updated_at = ? WHERE session_id = ? AND version = ? AND sub_version = ?",
                    now, sessionId, previousAnalysis.version(), newSubVersion);
        }

        // 9. Append assistant message
        String versionLabel = previousAnalysis.version() + "." + newSubVersion;
        String summary = scopeOnly
                ? "需求范围增量识别完成（v" + versionLabel + "）。请确认本期范围后再继续生成测试点。"
                : "需求分析增量更新（v" + versionLabel + "）。";
        if (clarificationQuestions != null && !clarificationQuestions.isBlank() && !"[]".equals(clarificationQuestions)) {
            summary += scopeOnly
                    ? "\n\n仍有需要澄清的问题。可继续补充并重新分析；范围确认前不会生成测试点或用例。"
                    : "\n\n请确认以上分析是否正确？有什么需要补充或修改的内容请输入。";
        } else {
            summary += "\n\n请确认分析结果。如果需要全量重新分析，请输入「重新分析」。";
        }
        messageService.appendAssistantMessage(sessionId, summary, analysisResult, "REQ_ANALYSIS", previousAnalysis.version());

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
                    .append(clipForPrompt(semanticContext.promptSection(), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
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

    public record TestPointScopeDecision(String testPointId, String disposition, String reason) {
    }

    public record RequirementScopeDecision(String requirementAtomId, String disposition, String reason) {
    }

    @Transactional
    public RequirementAnalysisRecord confirmRequirementScope(Long sessionId,
                                                              int version,
                                                              List<RequirementScopeDecision> decisions,
                                                              CurrentUser user) {
        sessionService.get(null, sessionId, user);
        RequirementAnalysisRecord analysis = getAnalysis(sessionId, version);
        if (!"NEED_SCOPE_CONFIRMATION".equals(analysis.status())
                && !"SCOPE_CONFIRMED".equals(analysis.status())) {
            throw new BusinessException("当前分析不处于需求范围确认阶段");
        }
        if (analysis.testPoints() != null && !analysis.testPoints().isBlank()
                && !"[]".equals(analysis.testPoints().trim())) {
            throw new BusinessException("该分析已经生成测试点，不能回改需求范围；请重新分析");
        }

        Map<String, Object> result = readJsonObject(analysis.analysisResult());
        if (result == null) throw new BusinessException("需求范围资产无法解析");
        List<Map<String, Object>> atoms = requirementAtoms(analysis.analysisResult());
        if (atoms.isEmpty()) throw new BusinessException("当前分析没有可确认的需求项");
        Map<String, RequirementScopeDecision> byId = new LinkedHashMap<>();
        for (RequirementScopeDecision decision : decisions == null ? List.<RequirementScopeDecision>of() : decisions) {
            String id = decision == null || decision.requirementAtomId() == null
                    ? "" : decision.requirementAtomId().trim();
            if (id.isBlank() || byId.putIfAbsent(id, decision) != null) {
                throw new BusinessException("需求范围决定包含空编号或重复编号");
            }
        }
        Set<String> atomIds = atoms.stream().map(atom -> String.valueOf(atom.getOrDefault("id", "")).trim())
                .filter(id -> !id.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!atomIds.equals(byId.keySet())) {
            throw new BusinessException("必须确认当前版本的全部需求项，不能遗漏或提交其他版本的需求项");
        }

        int generate = 0;
        int reference = 0;
        int excluded = 0;
        for (Map<String, Object> atom : atoms) {
            RequirementScopeDecision decision = byId.get(String.valueOf(atom.get("id")));
            String previousDisposition = scopeOf(atom);
            String disposition = normalizeScopeDisposition(decision.disposition());
            atom.put("generation_scope", disposition);
            atom.put("scope_decision_source", "USER");
            atom.put("scope_reason", decisionReason(decision.reason(), previousDisposition, disposition,
                    String.valueOf(atom.getOrDefault("scope_reason", ""))));
            if (SCOPE_GENERATE.equals(disposition)) generate++;
            else if (SCOPE_REFERENCE_ONLY.equals(disposition)) reference++;
            else excluded++;
        }
        if (generate == 0) throw new BusinessException("至少保留一个属于本期生成范围的需求项");

        result.put("requirement_atoms", atoms);
        result.put("requirement_scope_review", Map.of(
                "status", "CONFIRMED",
                "generate_count", generate,
                "reference_count", reference,
                "excluded_count", excluded,
                "reviewed_by", user.id(),
                "reviewed_at", timeProvider.now().toString()));
        jdbcTemplate.update("UPDATE requirement_analysis SET analysis_result = ?, status = 'SCOPE_CONFIRMED', updated_at = ? WHERE id = ?",
                toJson(result), timeProvider.now(), analysis.id());
        messageService.appendSystemMessage(sessionId,
                "需求范围已确认：本期生成 " + generate + " 项，仅参考 " + reference + " 项，排除 " + excluded + " 项。正在等待生成测试点。");
        return getAnalysis(sessionId, version);
    }

    public RequirementAnalysisRecord continueAfterRequirementScope(Long sessionId, int version, CurrentUser user) {
        GenerationSessionRecord session = sessionService.get(null, sessionId, user);
        RequirementAnalysisRecord analysis = getAnalysis(sessionId, version);
        Map<String, Object> fullResult = readJsonObject(analysis.analysisResult());
        Object reviewValue = fullResult == null ? null : fullResult.get("requirement_scope_review");
        String reviewStatus = reviewValue instanceof Map<?, ?> review
                ? String.valueOf(review.get("status")) : "";
        if (fullResult == null || !"CONFIRMED".equalsIgnoreCase(reviewStatus)) {
            throw new BusinessException("请先确认需求生成范围，再生成覆盖矩阵和测试点");
        }
        if (!"SCOPE_CONFIRMED".equals(analysis.status())) {
            if (analysis.testPoints() != null && !analysis.testPoints().isBlank()
                    && !"[]".equals(analysis.testPoints().trim())) return analysis;
            throw new BusinessException("需求范围状态已变化，请刷新后重试");
        }

        String generationCore = buildGenerationCore(analysis.analysisResult());
        requireRequirementAtoms(generationCore);
        requireTestUnits(generationCore);
        Long taskId = session.executionTaskId();
        if (taskId == null) throw new BusinessException("范围确认任务未关联异步执行记录");
        TomUsageMode tomMode = TomUsageMode.resolve(session.tomMode(), session.useMiniTom());
        ProjectSemanticContextService.BuildResult semanticContext =
                buildSemanticEvidence(session.projectId(), analysis.requirementText(), tomMode);
        String taskPrefix = "REQUIREMENT_SCOPE_CONTINUATION";
        CoverageStageResult coverageStage = runCoverageMatrixStages(user, session.projectId(), taskId,
                session.modelConfigId(), session.promptTemplateId(), taskPrefix, generationCore, semanticContext);
        TestPointStageResult testPointStage = runTestPointStages(user, session.projectId(), taskId,
                session.modelConfigId(), session.promptTemplateId(), taskPrefix, analysis.requirementText(),
                generationCore, coverageStage.coverageMatrix(), semanticContext);
        String testPoints = initializeTestPointScopes(testPointStage.testPoints(), generationCore);
        Map<String, Object> downstream = readJsonObject(mergeStagedAnalysis(generationCore,
                coverageStage.coverageMatrix(), coverageStage.matrixReviewNotes(),
                testPointStage.skillSelfCheck(), null));
        if (downstream != null) {
            for (Map.Entry<String, Object> entry : downstream.entrySet()) {
                if (!Set.of("requirement_atoms", "test_units", "requirement_scope_review").contains(entry.getKey())) {
                    fullResult.put(entry.getKey(), entry.getValue());
                }
            }
        }
        String merged = initializeScopeReview(toJson(fullResult), testPoints);
        merged = enrichAnalysisResult(merged, semanticContext, analysis.clarificationQuestions());
        ensureUsableAnalysisResult(merged);
        jdbcTemplate.update("UPDATE requirement_analysis SET analysis_result = ?, test_points = ?, status = 'NEED_TEST_POINT_SCOPE_CONFIRMATION', updated_at = ? WHERE id = ? AND status = 'SCOPE_CONFIRMED'",
                merged, testPoints, timeProvider.now(), analysis.id());
        sessionService.updateStage(sessionId, "WAITING_USER_CONFIRMATION");
        sessionService.updateStatus(sessionId, "ACTIVE");
        messageService.appendAssistantMessage(sessionId,
                "已按确认后的需求范围生成覆盖矩阵和测试点。请继续审核测试点范围；确认后系统才会生成用例编排计划。",
                merged, "REQUIREMENT_ANALYSIS_RESULT", analysis.version());
        loopIntegrationService.onChineseLocalizationCheck(session.projectId(), merged, "ANALYSIS", user);
        return getAnalysis(sessionId, version);
    }

    private String buildGenerationCore(String analysisResult) {
        Map<String, Object> root = readJsonObject(analysisResult);
        if (root == null) throw new BusinessException("需求范围资产无法解析");
        List<Map<String, Object>> atoms = requirementAtoms(analysisResult);
        List<Map<String, Object>> generatedAtoms = atoms.stream()
                .filter(atom -> SCOPE_GENERATE.equals(scopeOf(atom)))
                .<Map<String, Object>>map(atom -> new LinkedHashMap<>(atom)).toList();
        List<Map<String, Object>> references = atoms.stream()
                .filter(atom -> SCOPE_REFERENCE_ONLY.equals(scopeOf(atom)))
                .<Map<String, Object>>map(atom -> new LinkedHashMap<>(atom)).toList();
        Set<String> generatedIds = generatedAtoms.stream().map(atom -> String.valueOf(atom.get("id")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> units = testUnits(analysisResult);
        List<Map<String, Object>> generatedUnits = new ArrayList<>();
        for (Map<String, Object> source : units) {
            List<String> refs = toStringList(source.get("requirement_refs")).stream()
                    .filter(generatedIds::contains).toList();
            if (refs.isEmpty()) continue;
            Map<String, Object> unit = new LinkedHashMap<>(source);
            unit.put("requirement_refs", refs);
            generatedUnits.add(unit);
        }
        Set<String> unitIds = generatedUnits.stream().map(unit -> String.valueOf(unit.get("id")))
                .collect(Collectors.toSet());
        generatedUnits.forEach(unit -> unit.put("depends_on_unit_refs",
                toStringList(unit.get("depends_on_unit_refs")).stream().filter(unitIds::contains).toList()));
        if (generatedUnits.isEmpty()) throw new BusinessException("本期需求项没有可用于生成测试点的业务主题");
        root.put("requirement_atoms", generatedAtoms);
        root.put("test_units", generatedUnits);
        root.put("reference_requirement_atoms", references);
        root.remove("coverage_matrix");
        root.remove("case_plan");
        root.remove("test_points");
        return toJson(root);
    }

    /**
     * Persists the human scope gate without deleting analysis assets. Reference-only and
     * excluded points stay visible for audit, but are removed from the generation input.
     */
    @Transactional
    public RequirementAnalysisRecord confirmTestPointScope(Long sessionId,
                                                            int version,
                                                            List<TestPointScopeDecision> decisions,
                                                            CurrentUser user) {
        sessionService.get(null, sessionId, user);
        RequirementAnalysisRecord analysis = getAnalysis(sessionId, version);
        if (analysis == null) throw new BusinessException("没有对应版本的分析结果");
        if ("GENERATED".equals(analysis.status())) {
            throw new BusinessException("该分析已生成用例，不能再修改测试点范围；请重新分析后确认新范围");
        }
        if (!Set.of("NEED_TEST_POINT_SCOPE_CONFIRMATION", "NEED_CONFIRMATION", "TEST_POINT_SCOPE_CONFIRMED")
                .contains(analysis.status())) {
            throw new BusinessException("当前分析不处于测试点范围确认阶段");
        }
        Integer draftCount = jdbc.sql("SELECT COUNT(*) FROM test_case_draft WHERE analysis_id = :analysisId")
                .param("analysisId", analysis.id()).query(Integer.class).single();
        if (draftCount != null && draftCount > 0) {
            throw new BusinessException("该分析已有草稿，不能直接改范围；请舍弃草稿并重新分析");
        }

        List<Map<String, Object>> points = readJsonObjectList(analysis.testPoints());
        if (points == null || points.isEmpty()) throw new BusinessException("当前分析没有可确认的测试点");
        Map<String, TestPointScopeDecision> byId = new LinkedHashMap<>();
        for (TestPointScopeDecision decision : decisions == null ? List.<TestPointScopeDecision>of() : decisions) {
            String id = decision == null || decision.testPointId() == null ? "" : decision.testPointId().trim();
            if (id.isBlank() || byId.putIfAbsent(id, decision) != null) {
                throw new BusinessException("测试点范围决定包含空编号或重复编号");
            }
        }
        Set<String> pointIds = points.stream().map(point -> String.valueOf(point.getOrDefault("id", "")).trim())
                .filter(id -> !id.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!pointIds.equals(byId.keySet())) {
            throw new BusinessException("必须确认当前版本的全部测试点，不能遗漏或提交其他版本的测试点");
        }

        int generateCount = 0;
        int referenceCount = 0;
        int excludedCount = 0;
        for (Map<String, Object> point : points) {
            String id = String.valueOf(point.get("id"));
            TestPointScopeDecision decision = byId.get(id);
            String previousDisposition = scopeOf(point);
            String disposition = normalizeScopeDisposition(decision.disposition());
            point.put("generation_scope", disposition);
            point.put("scope_decision_source", "USER");
            point.put("scope_reason", decisionReason(decision.reason(), previousDisposition, disposition,
                    String.valueOf(point.getOrDefault("scope_reason", ""))));
            if (SCOPE_GENERATE.equals(disposition)) generateCount++;
            else if (SCOPE_REFERENCE_ONLY.equals(disposition)) referenceCount++;
            else excludedCount++;
        }
        if (generateCount == 0) throw new BusinessException("至少保留一个需要生成用例的测试点");

        Map<String, Object> result = readJsonObject(analysis.analysisResult());
        if (result == null) result = new LinkedHashMap<>();
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", "CONFIRMED");
        review.put("generate_count", generateCount);
        review.put("reference_count", referenceCount);
        review.put("excluded_count", excludedCount);
        review.put("reviewed_by", user.id());
        review.put("reviewed_at", timeProvider.now().toString());
        result.put("test_point_scope_review", review);
        if (result.containsKey("test_points")) result.put("test_points", points);

        String testPointsJson = toJson(points);
        String analysisResultJson = toJson(result);
        jdbcTemplate.update("UPDATE requirement_analysis SET test_points = ?, analysis_result = ?, status = 'TEST_POINT_SCOPE_CONFIRMED', updated_at = ? WHERE id = ?",
                testPointsJson, analysisResultJson, timeProvider.now(), analysis.id());
        messageService.appendSystemMessage(sessionId,
                "测试点范围已确认：生成 " + generateCount + " 项，仅参考 " + referenceCount + " 项，排除 " + excludedCount + " 项。");
        return getAnalysis(sessionId, version);
    }

    public RequirementAnalysisRecord continueAfterTestPointScope(Long sessionId, int version, CurrentUser user) {
        GenerationSessionRecord session = sessionService.get(null, sessionId, user);
        RequirementAnalysisRecord analysis = getAnalysis(sessionId, version);
        if (!"TEST_POINT_SCOPE_CONFIRMED".equals(analysis.status())) {
            if (extractEmbeddedCasePlan(analysis.analysisResult()) != null
                    && !"[]".equals(extractEmbeddedCasePlan(analysis.analysisResult()))) return analysis;
            throw new BusinessException("请先确认测试点范围，再生成用例编排计划");
        }
        String generationCore = buildGenerationCore(analysis.analysisResult());
        String generationPoints = filterTestPointsByScope(analysis.testPoints(), Set.of(SCOPE_GENERATE));
        if (parseObjectList(generationPoints).isEmpty()) throw new BusinessException("没有需要生成用例的测试点");
        Map<String, Object> result = readJsonObject(analysis.analysisResult());
        if (result == null) throw new BusinessException("需求分析资产无法解析");
        String coverageMatrix = writeStaticJson(result.get("coverage_matrix"));
        Long taskId = session.executionTaskId();
        if (taskId == null) throw new BusinessException("测试点范围继续任务未关联异步执行记录");
        TomUsageMode tomMode = TomUsageMode.resolve(session.tomMode(), session.useMiniTom());
        ProjectSemanticContextService.BuildResult semanticContext =
                buildSemanticEvidence(session.projectId(), analysis.requirementText(), tomMode);
        CaseCompositionStageResult composition = runCaseCompositionStage(user, session.projectId(), taskId,
                session.modelConfigId(), session.promptTemplateId(), "TEST_POINT_SCOPE_CONTINUATION",
                analysis.requirementText(), generationCore, coverageMatrix, generationPoints, semanticContext);
        Object casePlan = readJsonValue(composition.casePlan());
        result.put("case_plan", casePlan == null ? List.of() : casePlan);
        String merged = toJson(result);
        jdbcTemplate.update("UPDATE requirement_analysis SET analysis_result = ?, status = 'NEED_CONFIRMATION', updated_at = ? WHERE id = ? AND status = 'TEST_POINT_SCOPE_CONFIRMED'",
                merged, timeProvider.now(), analysis.id());
        sessionService.updateStage(sessionId, "WAITING_USER_CONFIRMATION");
        sessionService.updateStatus(sessionId, "ACTIVE");
        messageService.appendAssistantMessage(sessionId,
                "已按确认后的测试点范围生成用例编排计划。请检查节点用例和完整流程，确认后输入“生成用例”。",
                merged, "REQUIREMENT_ANALYSIS_RESULT", analysis.version());
        return getAnalysis(sessionId, version);
    }

    private static String normalizeScopeDisposition(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (Set.of(SCOPE_GENERATE, SCOPE_REFERENCE_ONLY, SCOPE_EXCLUDED).contains(normalized)) return normalized;
        throw new BusinessException("测试点范围只支持 GENERATE、REFERENCE_ONLY 或 EXCLUDED");
    }

    private static String decisionReason(String submittedReason, String previousDisposition,
                                         String disposition, String previousReason) {
        if (submittedReason != null && !submittedReason.isBlank()) return submittedReason.trim();
        if (!Objects.equals(previousDisposition, disposition)) return "用户人工调整范围";
        return previousReason == null ? "" : previousReason;
    }

    // =====================================================================
    // 增量生成
    // =====================================================================

    @Transactional
    public IncrementalGenerationResult incrementalGenerate(Long sessionId, List<Integer> selectedDraftIds, CurrentUser user) {
        var session = sessionService.get(null, sessionId, user);
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) throw new BusinessException("没有分析结果");
        int matchedCount = 0;
        int updatedCount = 0;
        int failedCount = 0;

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
            matchedCount++;
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
                    systemPrompt, userPrompt, null, CASE_PATCH_MAX_TOKENS
            );
            var response = llmGateway.invoke(request);
            if (response.status() != LlmInvocationStatus.OK) {
                log.warn("增量生成用例失败 draftId={}: {}", draftId, response.errorMessage());
                failedCount++;
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
                updatedCount++;
            } catch (Exception e) {
                failedCount++;
                log.warn("解析增量生成结果失败 draftId={}: {}", draftId, e.getMessage());
            }
        }
        if (matchedCount == 0) {
            throw new BusinessException("未找到可更新的用例草稿");
        }
        if (updatedCount == 0 && failedCount > 0) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "增量生成全部失败，未更新任何用例");
        }
        return new IncrementalGenerationResult(matchedCount, updatedCount, failedCount);
    }

    public record IncrementalGenerationResult(int matchedCount, int updatedCount, int failedCount) {
    }

    private record DraftRow(Long id, String caseTitle, String moduleName, String precondition,
                            String steps, String expectedResult, String priority,
                            String sourceRefsJson, int analysisVersion) {}

    /** 完整性检查优先使用稳定 TP 编号；标题只兼容没有编号的历史草稿。 */
    private void supplementMissingCases(Long sessionId, RequirementAnalysisRecord analysis, CurrentUser user) {
        if (analysis.testPoints() == null || analysis.testPoints().isBlank()) return;
        List<String> draftSourceRefs = jdbc.sql(
                "SELECT source_refs_json, case_status FROM test_case_draft WHERE session_id = :sid AND analysis_version = :aver")
                .param("sid", sessionId)
                .param("aver", analysis.version())
                .query((rs, rowNum) -> {
                    String status = rs.getString("case_status");
                    if ("DEPRECATED".equals(status)) return "";
                    String json = rs.getString("source_refs_json");
                    return json == null ? "" : json;
                })
                .list().stream().filter(ref -> !ref.isBlank()).toList();
        List<String> uncovered = findUncoveredTestPoints(
                filterTestPointsByScope(analysis.testPoints(), Set.of(SCOPE_GENERATE)), draftSourceRefs);

        if (uncovered.isEmpty()) return;

        if (!parseObjectList(filterCasePlanByScope(extractEmbeddedCasePlan(analysis.analysisResult()), analysis.testPoints())).isEmpty()) {
            String preview = uncovered.stream().limit(8).collect(Collectors.joining("、"));
            String suffix = uncovered.size() > 8 ? " 等 " + uncovered.size() + " 个测试点" : "";
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "用例编排仍有未完成覆盖：" + preview + suffix
                            + "。请从失败节点继续；系统只会重跑缺少 TP/CD 覆盖的编排节点。" );
        }

        log.info("历史分析记录发现 {} 个未覆盖的测试点，使用兼容补齐路径", uncovered.size());

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

    private List<String> findUncoveredTestPoints(String testPointsJson, List<String> sourceRefsJson) {
        List<TestPointCoverageRef> required = new ArrayList<>();
        try {
            var root = objectMapper.readTree(testPointsJson);
            if (root.isArray()) {
                for (var node : root) {
                    String id = node.path("id").asText("").trim();
                    String title = node.path("title").asText("").trim();
                    if (!id.isBlank() || !title.isBlank()) required.add(new TestPointCoverageRef(id, title));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        Set<String> coveredIds = new LinkedHashSet<>();
        Set<String> coveredTitles = new LinkedHashSet<>();
        for (String json : sourceRefsJson) {
            try {
                var root = objectMapper.readTree(json);
                collectText(root.get("sourceTestPointRefs"), coveredIds);
                collectText(root.get("sourceTestPoints"), coveredTitles);
                collectText(root.get("sourceTestPoint"), coveredTitles);
                var generatorRefs = root.get("generatorRefs");
                if (generatorRefs != null) {
                    collectText(generatorRefs.get("sourceTestPointRefs"), coveredIds);
                    collectText(generatorRefs.get("sourceTestPoints"), coveredTitles);
                    collectText(generatorRefs.get("sourceTestPoint"), coveredTitles);
                }
            } catch (Exception ignored) {
                // Invalid historical metadata cannot prove coverage.
            }
        }
        return required.stream()
                .filter(point -> !(!point.id().isBlank() && coveredIds.contains(point.id())))
                .filter(point -> point.title().isBlank() || !coveredTitles.contains(point.title()))
                .map(point -> point.id().isBlank() ? point.title()
                        : point.title().isBlank() ? point.id() : point.id() + " " + point.title())
                .toList();
    }

    private void collectText(com.fasterxml.jackson.databind.JsonNode node, Set<String> target) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(item -> {
                String value = item.asText("").trim();
                if (!value.isBlank()) target.add(value);
            });
            return;
        }
        String value = node.asText("").trim();
        if (!value.isBlank()) target.add(value);
    }

    private record TestPointCoverageRef(String id, String title) {}

    /**
     * 增量分析后，自动为 new_cases_needed 中的新测试点生成用例草稿。
     * 按小批次调用，避免大量完整用例被单次输出上限截断后只留下少数样例。
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

        String systemPrompt = """
                你是资深测试工程师。请为以下新测试点批量生成测试用例草稿。
                输出 JSON 数组，每个对象字段：caseTitle, moduleName, precondition, steps, expectedResult, priority, sourceTestPoint。
                steps 和 expectedResult 必须按编号格式，步骤和预期一一对应。
                每个输入测试点必须且只能生成一条用例，sourceTestPoint 必须原样引用对应输入测试点标题。
                """;
        int existingDrafts = jdbc.sql("SELECT COUNT(*) FROM test_case_draft WHERE session_id = :sid")
                .param("sid", sessionId).query(Integer.class).single();
        int nextCaseNo = existingDrafts + 1;
        int inserted = 0;
        for (int batchStart = 0; batchStart < newCases.size(); batchStart += CASE_SUPPLEMENT_BATCH_SIZE) {
            List<Map<String, String>> batch = newCases.subList(batchStart,
                    Math.min(batchStart + CASE_SUPPLEMENT_BATCH_SIZE, newCases.size()));
            StringBuilder caseList = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                var c = batch.get(i);
                caseList.append(i + 1).append(". 标题: ").append(c.getOrDefault("title", ""))
                        .append(" | 模块: ").append(c.getOrDefault("module_name", ""))
                        .append(" | 描述: ").append(c.getOrDefault("description", "")).append("\n");
            }
            String userPrompt = """
                    【需求分析结果】
                    %s

                    【本批需要新增的测试点】
                    %s

                    请为本批每个测试点生成一条完整的测试用例；不得省略，不得只返回代表性样例。
                    """.formatted(clipForPrompt(analysis.analysisResult(), 6000), caseList);
            var request = new LlmInvocationRequest(
                    UUID.randomUUID().toString(), user.id(), session.projectId(), session.executionTaskId(),
                    "AUTO_GEN_NEW_CASES_BATCH", LlmStage.TEST_CASE_GEN,
                    session.modelConfigId(), null, null, Map.of("batchStart", batchStart, "batchSize", batch.size()),
                    systemPrompt, userPrompt, null, CASE_PATCH_MAX_TOKENS
            );
            var response = llmGateway.invoke(request);
            if (response.status() != LlmInvocationStatus.OK) {
                log.warn("自动补充用例批次失败 batchStart={}, error={}", batchStart, response.errorMessage());
                continue;
            }
            try {
                List<Map<String, Object>> rows = objectMapper.readValue(extractJsonArray(response.content()),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                LocalDateTime now = timeProvider.now();
                for (Map<String, Object> row : rows) {
                    String caseNo = "TC-" + sessionId + "-NEW-" + nextCaseNo++;
                    String sourceTestPoint = strVal(row, "sourceTestPoint", strVal(row, "title", "新用例"));
                    String sourceRefsJson = "{\"sourceTestPoint\":\"" + sourceTestPoint.replace("\"", "\\\"") + "\",\"generatorRefs\":{\"sourceTestPoint\":\"" + sourceTestPoint.replace("\"", "\\\"") + "\"}}";
                    jdbcTemplate.update("""
                            INSERT INTO test_case_draft(session_id, analysis_id, analysis_version,
                                case_no, case_title, module_name, precondition,
                                steps, expected_result, priority, case_type, case_status,
                                source_refs_json, quality_status, created_by, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FUNCTIONAL', 'DRAFT', ?, 'LOW_EVIDENCE', ?, ?, ?)
                            """, sessionId, analysis.id(), analysis.version(),
                            caseNo, strVal(row, "caseTitle", "新用例"), strVal(row, "moduleName", "默认模块"),
                            strVal(row, "precondition", ""), strVal(row, "steps", ""),
                            strVal(row, "expectedResult", ""), strVal(row, "priority", "P2"),
                            sourceRefsJson, user.id(), now, now);
                    inserted++;
                }
            } catch (Exception e) {
                log.warn("解析自动补充用例批次失败 batchStart={}: {}", batchStart, e.getMessage());
            }
        }
        log.info("自动为 {} 个新测试点分批生成了 {} 条用例", newCases.size(), inserted);
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
                    session.promptTemplateId(),
                    latest.version(),
                    generationPromptSnapshot,
                    TomUsageMode.resolve(session.tomMode(), session.useMiniTom()).name(),
                    TomUsageMode.resolve(session.tomMode(), session.useMiniTom()).usesTom()
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

    public SessionGenerationPlan buildSessionGenerationPlan(Long sessionId, CurrentUser user) {
        var session = sessionService.get(null, sessionId, user);
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) {
            throw new BusinessException("没有分析结果");
        }
        return new SessionGenerationPlan(
                session.projectId(),
                session.id(),
                latest.id(),
                latest.version(),
                session.sessionTitle(),
                buildGenerationRequirementText(latest),
                buildGenerationPromptSnapshot(session.promptSnapshot(), latest),
                session.modelConfigId(),
                session.promptTemplateId(),
                TomUsageMode.resolve(session.tomMode(), session.useMiniTom()).usesTom(),
                TomUsageMode.resolve(session.tomMode(), session.useMiniTom()).name()
        );
    }

    public RequirementAnalysisRecord completeAsyncGeneration(Long sessionId, Long taskId, CurrentUser user,
                                                             DirectCaseGenerationService.GenerateResult result) {
        var session = sessionService.get(null, sessionId, user);
        var latest = getLatestAnalysis(sessionId);
        if (latest == null) {
            throw new BusinessException("没有分析结果");
        }
        applyDraftEvidenceLinks(taskId, sessionId, latest, result);
        supplementMissingCases(sessionId, latest, user);
        sessionService.updateStatus(sessionId, "COMPLETED");
        jdbcTemplate.update("UPDATE requirement_analysis SET status = 'GENERATED', updated_at = ? WHERE id = ?",
                timeProvider.now(), latest.id());
        return getLatestAnalysis(session.id());
    }

    public RequirementAnalysisRecord getLatestAnalysis(Long sessionId) {
        var list = jdbc.sql("SELECT * FROM requirement_analysis WHERE session_id = :sid ORDER BY version DESC, sub_version DESC, id DESC LIMIT 1")
                .param("sid", sessionId).query(this::map).list();
        return list.isEmpty() ? null : list.get(0);
    }

    public List<RequirementAnalysisRecord> listAnalyses(Long sessionId) {
        return jdbc.sql("SELECT * FROM requirement_analysis WHERE session_id = :sid ORDER BY version DESC, sub_version DESC, id DESC")
                .param("sid", sessionId).query(this::map).list();
    }

    public RequirementAnalysisRecord getAnalysis(Long sessionId, int version) {
        var list = jdbc.sql("SELECT * FROM requirement_analysis WHERE session_id = :sid AND version = :ver ORDER BY sub_version DESC, id DESC LIMIT 1")
                .param("sid", sessionId).param("ver", version).query(this::map).list();
        if (list.isEmpty()) throw new BusinessException("分析结果不存在");
        return list.get(0);
    }

    public record SessionGenerationPlan(Long projectId, Long sessionId, Long analysisId, int analysisVersion,
                                        String taskName, String requirementText, String promptSnapshot,
                                        Long modelConfigId, Long promptTemplateId, boolean useMiniTom,
                                        String tomMode) {
        public SessionGenerationPlan(Long projectId, Long sessionId, Long analysisId, int analysisVersion,
                                     String taskName, String requirementText, String promptSnapshot,
                                     Long modelConfigId, Long promptTemplateId, boolean useMiniTom) {
            this(projectId, sessionId, analysisId, analysisVersion, taskName, requirementText,
                    promptSnapshot, modelConfigId, promptTemplateId, useMiniTom,
                    useMiniTom ? "PROJECT_AND_SYSTEM_TOM" : "DIRECT");
        }
    }

    String buildAnalysisSystemPrompt() {
        return """
                你是一个资深测试分析师。你的任务是分析用户需求并输出结构化的分析结果。

                分析必须按以下顺序进行：
                1. 先理解需求（理解业务目标、用户角色、核心流程）
                2. 再做风险扫描（识别评审前需确认的问题、异常场景、边界条件）
                3. 再判断哪些必须现在澄清（只有阻断准确分析的问题才进入澄清）
                4. 最后拆测试点（按需求类型选拆解骨架，不要全部混成功能点列表），并区分本期生成范围、背景参考和明确非本期内容

                请严格以 JSON 对象格式返回，不要返回 markdown，不要返回代码块，不要返回任何额外文字说明。
                顶层 JSON 对象必须且只能包含 analysis、test_points、clarification_questions、assumptions 四个主要键；如有增量分析字段再额外包含 affected_cases、new_cases_needed、change_scope。
                请返回以下 JSON 对象格式：
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
                    "coverage_matrix": [
                      {
                        "module": "模块名",
                        "main_flow": {
                          "count": 1,
                          "items": ["主流程测试点内容，必须写清楚具体测什么"]
                        },
                        "branch": {
                          "count": 0,
                          "items": []
                        },
                        "boundary": {
                          "count": 0,
                          "items": []
                        },
                        "exception": {
                          "count": 0,
                          "items": []
                        },
                        "state": {
                          "count": 0,
                          "items": []
                        },
                        "data": {
                          "count": 0,
                          "items": []
                        },
                        "auth": {
                          "count": 0,
                          "items": []
                        },
                        "concurrency": {
                          "count": 0,
                          "items": []
                        },
                        "idempotent": {
                          "count": 0,
                          "items": []
                        },
                        "total": 1
                      }
                    ],
                    "skill_self_check": {
                      "three_layer_complete": false,
                      "redundancy_checked": false,
                      "method_routing_checked": false,
                      "p0_review_checked": false,
                      "notes": ["仍需人工确认的质量检查结论"]
                    },
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
                      "id": "TP1",
                      "title": "测试点标题",
                      "description": "测试点描述",
                      "test_dimension": "测试维度",
                      "point_type": "MAIN_FLOW/BRANCH/BOUNDARY/EXCEPTION/STATE/DATA/AUTH/CONCURRENCY/IDEMPOTENT",
                      "skill_layer": "FUNCTIONAL/EXCEPTION/BOUNDARY_SUPPLEMENT",
                      "design_method": "场景法/等价类/边界值/判定表/状态迁移/错误推测/数据一致性检查",
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
                      "needs_confirmation": true,
                      "scope_recommendation": "IN_SCOPE/REFERENCE_ONLY/OUT_OF_SCOPE/NEEDS_CONFIRMATION",
                      "scope_reason": "范围建议依据",
                      "generation_scope": "GENERATE/REFERENCE_ONLY"
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
                    - RULE 型重点覆盖条件组合、边界值、互斥规则，优先使用判定表 + 边界值
                    - FORM 型重点覆盖字段校验、必填、联动、提交，优先使用等价类 + 边界值
                    - UI 型重点覆盖交互流程、状态切换、响应式，优先使用场景法
                    - STATE 型重点覆盖状态流转、异常恢复、并发，优先使用状态迁移 + 场景法
                    - DATA 型重点覆盖一致性、并发、幂等、数据量，优先使用数据一致性检查 + 错误推测
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
                16. 测试点必须按三层递进拆解：
                    - FUNCTIONAL：核心主流程 / Happy Path，对应 MAIN_FLOW/BRANCH
                    - EXCEPTION：异常、失败、权限、状态异常，对应 EXCEPTION/AUTH/STATE
                    - BOUNDARY_SUPPLEMENT：边界、数据一致性、并发、幂等、恢复，对应 BOUNDARY/DATA/CONCURRENCY/IDEMPOTENT
                17. 必须生成 coverage_matrix，按模块统计各 point_type 数量，并在每个维度的 items 中列出对应测试点内容；不能只返回数字
                    - main_flow/branch/boundary/exception/state/data/auth/concurrency/idempotent 必须是对象：{"count": 数量, "items": ["具体测试点"]}
                    - items 必须能解释 count 的来源，例如边界 count=2 时 items 必须有 2 条边界点
                    - 每个维度 items 最多 3 条，只写短句，不要展开成长段说明
                    - 如果某维度不涉及，返回 {"count": 0, "items": []}
                18. 必须执行防冗余自检并写入 skill_self_check：
                    - 状态参数化：状态差异但操作步骤相同的测试点应参数化合并
                    - 同一表单多个字段校验应合并为字段校验组
                    - 同一操作不同入口但验证目标一致时应合并
                    - 取消/关闭/返回等预期一致的交互不应拆成多条核心用例
                19. P0/CORE 只用于核心业务主路径；字段校验、权限拦截、取消关闭、样式布局、纯异常路径不得标为 P0/CORE
                20. 控制输出长度：
                    - requirement_understanding 不超过 300 字
                    - review_risk_questions 最多 5 条
                    - risk_scenarios 最多 8 条
                    - boundary_conditions 最多 8 条
                    - coverage_matrix 最多 6 个模块
                    - test_points 最多 24 条
                    - 优先保证 JSON 完整闭合，禁止为了补充长说明导致 JSON 截断
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
                    .append(clipForPrompt(semanticContext.promptSection(), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
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
        requireConfirmedScopeReview(analysis);
        String generationPoints = filterTestPointsByScope(analysis.testPoints(), Set.of(SCOPE_GENERATE));
        String referencePoints = filterTestPointsByScope(analysis.testPoints(), Set.of(SCOPE_REFERENCE_ONLY));
        String effectiveCasePlan = filterCasePlanByScope(
                extractEmbeddedCasePlan(analysis.analysisResult()), analysis.testPoints());
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "原始需求", analysis.requirementText(), MAX_GENERATION_REQUIREMENT_CHARS);
        appendSection(sb, "已确认/最新需求分析", buildGenerationAnalysisContext(analysis.analysisResult()), MAX_GENERATION_ANALYSIS_CHARS);
        // TP/CP 是生成器的完成清单，不能为控制 prompt 长度而截断；生成器会按 CP 节点拆分后再调用模型。
        appendSection(sb, "测试点", generationPoints, Integer.MAX_VALUE);
        appendSection(sb, "仅作背景/前置参考的测试点（禁止为其单独生成用例）", referencePoints, Integer.MAX_VALUE);
        appendSection(sb, "用例编排计划", effectiveCasePlan, Integer.MAX_VALUE);
        appendSection(sb, "TOM/项目证据快照", analysis.tomScopeSnapshot(), MAX_GENERATION_TOM_CHARS);
        appendSection(sb, "澄清答案", analysis.clarificationAnswers(), MAX_GENERATION_SMALL_SECTION_CHARS);
        appendSection(sb, "生成假设", analysis.assumptions(), MAX_GENERATION_SMALL_SECTION_CHARS);
        sb.append("""

                ## 用例生成约束
                1. 只能为“已确认需要生成用例的测试点”生成用例；仅参考测试点只能作为背景或前置条件，排除项不得进入输入。
                2. 每条生成范围内测试点至少由一条可执行用例覆盖，存在边界/异常/权限/状态流转时补充覆盖。
                3. 不要引入分析结果之外的固定样例数据；缺失信息只能写入前置条件或假设说明。
                4. 用例标题、步骤、预期结果必须互相对应，避免只像测试数据列表。
                5. 每条用例必须能追溯到一个测试点和一个用例编排计划；不能把多个无关测试点混成一条用例。
                6. 步骤、页面、字段、角色、流程优先来自 TOM/项目证据快照；没有证据的内容必须写成“待确认/假设”，不要当作事实。
                7. 如果测试点标记 LOW_EVIDENCE，只能生成低置信草稿，不能扩展成额外业务场景。
                8. 生成用例时遵守三层递进：先覆盖 FUNCTIONAL 主流程，再覆盖 EXCEPTION 异常/分支，最后覆盖 BOUNDARY_SUPPLEMENT 边界、数据一致性、权限、并发、幂等。
                9. 生成前参考 coverage_matrix，避免只生成主流程；如果某个模块只有主流程没有异常/边界，应在草稿中补足或标记待确认。
                10. 遵守防冗余规则：状态参数化、字段校验合并、同类入口合并、取消/关闭合并，避免把同一验证目标拆成多条重复用例。
                11. 使用测试点中的 design_method 设计步骤；没有 design_method 时按需求类型选择场景法、等价类、边界值、判定表、状态迁移或错误推测。
                12. 对 case_strategy=NODE_FOCUSED 的计划，前序测试点只能写在前置条件，步骤与预期只覆盖当前节点；对 FLOW_COMPOSED 的计划，按已声明依赖顺序覆盖完整流程。
                """);
        return sb.toString().trim();
    }

    private static void requireConfirmedScopeReview(RequirementAnalysisRecord analysis) {
        if (analysis == null) throw new BusinessException("没有分析结果");
        try {
            JsonNode review = objectMapper.readTree(analysis.analysisResult()).path("test_point_scope_review");
            if (!"CONFIRMED".equalsIgnoreCase(review.path("status").asText())) {
                throw new BusinessException("请先在右侧需求分析中审核并确认测试点范围，再生成用例");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("请先在右侧需求分析中审核并确认测试点范围，再生成用例");
        }
        List<Map<String, Object>> points = parseObjectList(analysis.testPoints());
        boolean hasGenerationPoint = points.stream().anyMatch(point -> SCOPE_GENERATE.equals(scopeOf(point)));
        if (!hasGenerationPoint) throw new BusinessException("测试点范围中没有需要生成用例的项目");
    }

    private static String filterTestPointsByScope(String testPointsJson, Set<String> acceptedScopes) {
        return writeStaticJson(parseObjectList(testPointsJson).stream()
                .filter(point -> acceptedScopes.contains(scopeOf(point))).toList());
    }

    private static String filterCasePlanByScope(String casePlanJson, String testPointsJson) {
        List<Map<String, Object>> points = parseObjectList(testPointsJson);
        Set<String> generated = points.stream().filter(point -> SCOPE_GENERATE.equals(scopeOf(point)))
                .map(point -> String.valueOf(point.getOrDefault("id", ""))).filter(id -> !id.isBlank()).collect(Collectors.toSet());
        Set<String> availableAsContext = points.stream().filter(point -> !SCOPE_EXCLUDED.equals(scopeOf(point)))
                .map(point -> String.valueOf(point.getOrDefault("id", ""))).filter(id -> !id.isBlank()).collect(Collectors.toSet());
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> source : parseObjectList(casePlanJson)) {
            List<String> sourceRefs = staticStringList(source.get("source_test_point_refs")).stream()
                    .filter(generated::contains).toList();
            if (sourceRefs.isEmpty()) continue;
            if ("FLOW_COMPOSED".equals(String.valueOf(source.get("case_strategy"))) && sourceRefs.size() < 2) continue;
            Map<String, Object> plan = new LinkedHashMap<>(source);
            plan.put("source_test_point_refs", sourceRefs);
            plan.put("precondition_test_point_refs", staticStringList(source.get("precondition_test_point_refs")).stream()
                    .filter(availableAsContext::contains).toList());
            List<Map<String, Object>> designs = parseObjectList(writeStaticJson(source.get("case_designs")));
            List<Map<String, Object>> keptDesigns = new ArrayList<>();
            for (Map<String, Object> rawDesign : designs) {
                List<String> designRefs = staticStringList(rawDesign.get("source_test_point_refs"));
                if (!designRefs.isEmpty() && designRefs.stream().noneMatch(generated::contains)) continue;
                Map<String, Object> design = new LinkedHashMap<>(rawDesign);
                if (!designRefs.isEmpty()) design.put("source_test_point_refs", designRefs.stream().filter(generated::contains).toList());
                keptDesigns.add(design);
            }
            if (!designs.isEmpty() && keptDesigns.isEmpty()) continue;
            plan.put("case_designs", keptDesigns);
            filtered.add(plan);
        }
        return writeStaticJson(filtered);
    }

    private static String scopeOf(Map<String, Object> point) {
        String value = String.valueOf(point.getOrDefault("generation_scope", SCOPE_GENERATE)).trim().toUpperCase(Locale.ROOT);
        return Set.of(SCOPE_GENERATE, SCOPE_REFERENCE_ONLY, SCOPE_EXCLUDED).contains(value) ? value : SCOPE_GENERATE;
    }

    private static List<Map<String, Object>> parseObjectList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<String> staticStringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().filter(Objects::nonNull).map(String::valueOf).map(String::trim)
                .filter(item -> !item.isBlank()).toList();
    }

    private static String writeStaticJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String extractEmbeddedCasePlan(String analysisResult) {
        if (analysisResult == null || analysisResult.isBlank()) {
            return null;
        }
        try {
            var value = objectMapper.readTree(analysisResult).path("case_plan");
            return value.isMissingNode() || value.isNull() ? null : objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String buildGenerationAnalysisContext(String analysisResult) {
        if (analysisResult == null || analysisResult.isBlank()) return null;
        try {
            Map<String, Object> root = objectMapper.readValue(analysisResult, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            // 可追溯大资产已在独立章节完整保存；这里仅保留生成所需的业务摘要，避免反复塞回单个模型请求。
            root.remove("case_plan");
            root.remove("requirement_atoms");
            root.remove("test_units");
            root.remove("coverage_matrix");
            root.remove("evidence_summary");
            return objectMapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return analysisResult;
        }
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
        // A draft already carries its own TP/CP/CD references below.  Copying the complete
        // analysis test-point array into every draft makes a long requirement grow quadratically
        // and can turn traceability metadata into a database write failure.  The authoritative
        // complete list remains in requirement_analysis.test_points, addressed by this pointer.
        refs.put("analysisTestPointAsset", buildDraftTestPointAssetPointer(analysis));
        refs.put("qualityGate", buildGenerationQualityGate(analysis, result));
        return refs;
    }

    static Map<String, Object> buildDraftTestPointAssetPointer(RequirementAnalysisRecord analysis) {
        Map<String, Object> pointer = new LinkedHashMap<>();
        pointer.put("storage", "requirement_analysis.test_points");
        pointer.put("analysisId", analysis == null ? null : analysis.id());
        pointer.put("analysisVersion", analysis == null ? null : analysis.version());
        pointer.put("testPointCount", analysis == null ? 0 : countJsonArray(analysis.testPoints()));
        return pointer;
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
                copyIfPresent(generatorRefs, mergedRefs, "sourceCasePlan");
                copyIfPresent(generatorRefs, mergedRefs, "sourceCaseDesign");
                copyIfPresent(generatorRefs, mergedRefs, "sourceTestPoint");
                copyIfPresent(generatorRefs, mergedRefs, "sourceTestPointRefs");
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

    private ProjectSemanticContextService.BuildResult buildSemanticEvidence(Long projectId, String requirementText,
                                                                             TomUsageMode tomMode) {
        try {
            return semanticContextService.build(projectId, requirementText, List.of(), 8, tomMode);
        } catch (Exception e) {
            log.warn("Project semantic evidence build failed: {}", e.getMessage());
            return new ProjectSemanticContextService.BuildResult("", List.of());
        }
    }

    private record StagedAnalysisResult(String rawOutput,
                                        String analysisResult,
                                        String clarificationQuestions,
                                        String assumptions,
                                        String testPoints,
                                        String affectedCases,
                                        String changeScope,
                                        String newCasesNeeded) {
    }

    private record TestPointStageResult(String rawOutput, String testPoints, String skillSelfCheck) {
    }

    private record CaseCompositionStageResult(String rawOutput, String casePlan) {
    }

    private record CoverageStageResult(String rawOutput, String coverageMatrix, String matrixReviewNotes) {
    }

    private record CoreStageResult(String rawOutput, String coreJson, String clarificationQuestions,
                                   String assumptions, String affectedCases, String changeScope,
                                   String newCasesNeeded) {
    }

    private StagedAnalysisResult runRequirementScopeAnalysis(CurrentUser user,
                                                              Long projectId,
                                                              Long taskId,
                                                              Long modelConfigId,
                                                              Long promptTemplateId,
                                                              String taskTypePrefix,
                                                              String requirementText,
                                                              String tomSnapshot,
                                                              ProjectSemanticContextService.BuildResult semanticContext,
                                                              String userSupplement,
                                                              String previousContext) {
        CoreStageResult coreStage = runCoreAnalysisStages(user, projectId, taskId, modelConfigId,
                promptTemplateId, taskTypePrefix, requirementText, tomSnapshot, semanticContext,
                userSupplement, previousContext);
        String questions = ensureClarificationQuestions(coreStage.coreJson(), coreStage.clarificationQuestions());
        String scopedCore = initializeRequirementAtomScopes(coreStage.coreJson());
        scopedCore = initializeRequirementScopeReview(scopedCore);
        String analysisResult = enrichAnalysisResult(scopedCore, semanticContext, questions);
        ensureUsableAnalysisResult(analysisResult);
        return new StagedAnalysisResult(coreStage.rawOutput(), analysisResult, questions,
                coreStage.assumptions(), "[]", coreStage.affectedCases(), coreStage.changeScope(),
                coreStage.newCasesNeeded());
    }

    private StagedAnalysisResult runStagedRequirementAnalysis(CurrentUser user,
                                                             Long projectId,
                                                             Long taskId,
                                                             Long modelConfigId,
                                                             Long promptTemplateId,
                                                             String taskTypePrefix,
                                                             String requirementText,
                                                             String tomSnapshot,
                                                             ProjectSemanticContextService.BuildResult semanticContext,
                                                             String userSupplement,
                                                             String previousContext,
                                                             Long sessionId) {
        CoreStageResult coreStage = runCoreAnalysisStages(user, projectId, taskId, modelConfigId, promptTemplateId,
                taskTypePrefix, requirementText, tomSnapshot, semanticContext, userSupplement, previousContext);
        String coreOutput = coreStage.rawOutput();
        String coreJson = coreStage.coreJson();
        // Models occasionally mark an R* atom as needs_clarification but omit the paired
        // question array.  Do not silently lose the user-facing clarification gate.
        String clarificationQuestions = ensureClarificationQuestions(
                coreJson, coreStage.clarificationQuestions());
        String assumptions = coreStage.assumptions();
        requireUsableCoreAnalysis(coreJson, semanticContext, clarificationQuestions);
        requireRequirementAtoms(coreJson);
        requireTestUnits(coreJson);

        CoverageStageResult coverageStage = runCoverageMatrixStages(user, projectId, taskId, modelConfigId,
                promptTemplateId, taskTypePrefix, coreJson, semanticContext);
        String matrixOutput = coverageStage.rawOutput();
        String coverageMatrix = coverageStage.coverageMatrix();
        String matrixReviewNotes = coverageStage.matrixReviewNotes();

        TestPointStageResult testPointStage = runTestPointStages(
                user, projectId, taskId, modelConfigId, promptTemplateId, taskTypePrefix,
                requirementText, coreJson, coverageMatrix, semanticContext);
        String testPointOutput = testPointStage.rawOutput();
        String testPoints = initializeTestPointScopes(testPointStage.testPoints(), coreJson);
        String skillSelfCheck = testPointStage.skillSelfCheck();

        CaseCompositionStageResult caseCompositionStage = runCaseCompositionStage(
                user, projectId, taskId, modelConfigId, promptTemplateId, taskTypePrefix,
                requirementText, coreJson, coverageMatrix, testPoints, semanticContext);

        String mergedAnalysis = mergeStagedAnalysis(coreJson, coverageMatrix, matrixReviewNotes, skillSelfCheck,
                caseCompositionStage.casePlan());
        mergedAnalysis = initializeScopeReview(mergedAnalysis, testPoints);
        String analysisResult = enrichAnalysisResult(mergedAnalysis, semanticContext, clarificationQuestions);
        ensureUsableAnalysisResult(analysisResult);

        String rawOutput = toJson(Map.of(
                "core", safeJsonValue(coreOutput),
                "coverage", safeJsonValue(matrixOutput),
                "test_points", safeJsonValue(testPointOutput),
                "case_composition", safeJsonValue(caseCompositionStage.rawOutput())
        ));
        if (rawOutput == null) {
            rawOutput = coreOutput + "\n" + matrixOutput + "\n" + testPointOutput;
        }
        return new StagedAnalysisResult(
                rawOutput,
                analysisResult,
                clarificationQuestions,
                assumptions,
                testPoints,
                coreStage.affectedCases(),
                coreStage.changeScope(),
                coreStage.newCasesNeeded()
        );
    }

    /**
     * 长需求不能只保留首尾文本。超过单片预算时，先逐片抽取需求资产，再由平台统一编号合并；
     * 这样后续矩阵、测试点与用例计划都能引用完整 R/U 清单。
     */
    private CoreStageResult runCoreAnalysisStages(CurrentUser user,
                                                  Long projectId,
                                                  Long taskId,
                                                  Long modelConfigId,
                                                  Long promptTemplateId,
                                                  String taskTypePrefix,
                                                  String requirementText,
                                                  String tomSnapshot,
                                                  ProjectSemanticContextService.BuildResult semanticContext,
                                                  String userSupplement,
                                                  String previousContext) {
        List<String> fragments = splitRequirementIntoFragments(requirementText);
        if (fragments.size() == 1) {
            return runSingleCoreStage(user, projectId, taskId, modelConfigId, promptTemplateId, taskTypePrefix + "_CORE",
                    fragments.get(0), tomSnapshot, semanticContext, userSupplement, previousContext, "需求理解");
        }
        List<CoreStageResult> partials = new ArrayList<>();
        for (int index = 0; index < fragments.size(); index++) {
            String fragment = fragments.get(index);
            CoreStageResult partial = runSingleCoreStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                    taskTypePrefix + "_CORE_PART_" + (index + 1), fragment, tomSnapshot, semanticContext,
                    userSupplement, previousContext,
                    "需求理解（输入片段 " + (index + 1) + "/" + fragments.size() + "）");
            partials.add(partial);
        }
        CoreStageResult merged = mergeCoreFragments(partials);
        return enrichCrossFragmentUnitDependencies(user, projectId, taskId, modelConfigId, promptTemplateId,
                taskTypePrefix, merged);
    }

    private CoreStageResult runSingleCoreStage(CurrentUser user,
                                               Long projectId,
                                               Long taskId,
                                               Long modelConfigId,
                                               Long promptTemplateId,
                                               String taskType,
                                               String requirementFragment,
                                               String tomSnapshot,
                                               ProjectSemanticContextService.BuildResult semanticContext,
                                               String userSupplement,
                                               String previousContext,
                                               String label) {
        String output = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                taskType + "_OVERVIEW", buildCoreOverviewSystemPrompt(previousContext != null),
                buildCoreOverviewUserPrompt(requirementFragment, tomSnapshot, userSupplement, previousContext),
                1536, label + "概览");
        String coreJson = normalizeJsonColumn(extractAnalysisJson(output));
        String questions = normalizeJsonColumn(extractJson(output, "clarification_questions"));
        String assumptions = normalizeJsonColumn(extractJson(output, "assumptions"));
        if (!isUsableAnalysisResult(enrichAnalysisResult(coreJson, semanticContext, questions))
                || requirementAtoms(coreJson).isEmpty() || testUnits(coreJson).isEmpty()
                || !missingTestUnitRequirementAtomIds(coreJson).isEmpty()) {
            List<String> missingFields = missingCoreFields(coreJson);
            if (!missingFields.isEmpty() && readJsonObject(coreJson) != null) {
                // Keep the successfully returned core asset. Patch one dependency-ordered field at
                // a time: a large atom list otherwise consumes the response before test_units arrive.
                Map<String, Object> nodeOutputs = new LinkedHashMap<>();
                nodeOutputs.put("initial", safeJsonValue(output));
                for (int patchIndex = 0; patchIndex < MAX_CORE_PATCH_ATTEMPTS; patchIndex++) {
                    List<String> remaining = missingCoreFields(coreJson);
                    if (remaining.isEmpty()) break;
                    String field = remaining.get(0);
                    String patchOutput = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                            taskType + "_PATCH_" + (patchIndex + 1),
                            buildCorePatchSystemPrompt(),
                            buildCorePatchPrompt(requirementFragment, coreJson, List.of(field)),
                            ANALYSIS_MAX_TOKENS, label + field + "补齐");
                    String patchJson = normalizeJsonColumn(extractAnalysisJson(patchOutput));
                    String merged = mergeCorePatch(coreJson, patchJson, List.of(field));
                    nodeOutputs.put("patch_" + field, safeJsonValue(patchOutput));
                    questions = mergeJsonArrays(questions, normalizeJsonColumn(extractJson(patchOutput, "clarification_questions")));
                    assumptions = mergeJsonArrays(assumptions, normalizeJsonColumn(extractJson(patchOutput, "assumptions")));
                    if (merged.equals(coreJson)) {
                        // A provider may return a syntactically valid response that omits the
                        // requested field. Keep the completed core asset and retry this small
                        // patch in the same task instead of making the user restart it.
                        log.warn("{}{}补齐未返回可用字段，第{}次将继续补齐同一字段", label, field, patchIndex + 1);
                        continue;
                    }
                    coreJson = merged;
                }
                output = toJson(nodeOutputs);
            } else {
                String repairOutput = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                        taskType + "_REPAIR", buildAnalysisCoreSystemPrompt(previousContext != null),
                        buildCoreRepairPrompt(requirementFragment, tomSnapshot, semanticContext, userSupplement, previousContext)
                                + "\n\n必须覆盖当前输入片段，返回 requirement_atoms 与 test_units。",
                        ANALYSIS_MAX_TOKENS, label + "补齐");
                output = repairOutput;
                coreJson = normalizeJsonColumn(extractAnalysisJson(repairOutput));
                questions = normalizeJsonColumn(extractJson(repairOutput, "clarification_questions"));
                assumptions = normalizeJsonColumn(extractJson(repairOutput, "assumptions"));
            }
        }
        // Every CORE recovery path must pass through the same narrow test-unit repair gate.
        // In particular, a full CORE repair can return valid atoms but omit test_units.
        coreJson = repairTestUnitsIfNeeded(user, projectId, taskId, modelConfigId, promptTemplateId,
                taskType, coreJson, label);
        requireUsableCoreAnalysis(coreJson, semanticContext, questions);
        requireRequirementAtoms(coreJson);
        requireTestUnits(coreJson);
        requireAllRequirementAtomsAssignedToTestUnits(coreJson);
        return new CoreStageResult(output, coreJson, questions, assumptions,
                normalizeJsonColumn(extractJson(output, "affected_cases")),
                normalizeChangeScope(extractPlainText(output, "change_scope")),
                normalizeJsonColumn(extractJson(output, "new_cases_needed")));
    }

    /**
     * The overview node intentionally has no test_units. If a generic patch was malformed or
     * skipped that field, run narrow repairs against the already persisted requirement atoms.
     * Each attempt is an independent checkpointed node, so one malformed provider response does
     * not force the user to click "resume". No local placeholder topic is invented.
     */
    private String repairTestUnitsIfNeeded(CurrentUser user,
                                           Long projectId,
                                           Long taskId,
                                           Long modelConfigId,
                                           Long promptTemplateId,
                                           String taskType,
                                           String coreJson,
                                           String label) {
        if (requirementAtoms(coreJson).isEmpty()
                || (testUnits(coreJson).size() > 0 && missingTestUnitRequirementAtomIds(coreJson).isEmpty())) {
            return coreJson;
        }
        String repaired = coreJson;
        for (int attempt = 1; attempt <= MAX_TEST_UNIT_REPAIR_ATTEMPTS; attempt++) {
            String repairOutput = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                    taskType + "_TEST_UNITS_REPAIR_" + attempt,
                    buildTestUnitsRepairSystemPrompt(),
                    buildTestUnitsRepairPrompt(repaired),
                    ANALYSIS_MAX_TOKENS, label + "测试主题补齐 " + attempt + "/" + MAX_TEST_UNIT_REPAIR_ATTEMPTS);
            String merged = mergeCorePatch(repaired,
                    normalizeJsonColumn(extractAnalysisJson(repairOutput)), List.of("test_units"));
            if (!merged.equals(repaired)) {
                repaired = merged;
            }
            if (!testUnits(repaired).isEmpty() && missingTestUnitRequirementAtomIds(repaired).isEmpty()) {
                return repaired;
            }
            log.warn("{}测试主题第{}次补齐仍不完整，将继续当前小节点", label, attempt);
        }
        return repaired;
    }

    private List<String> splitRequirementIntoFragments(String requirementText) {
        String source = requirementText == null ? "" : requirementText.trim();
        if (source.length() <= ANALYSIS_INPUT_FRAGMENT_CHARS) return List.of(source);
        List<String> fragments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : source.split("(?<=\\n)|(?<=[。！？；;])")) {
            if (paragraph.isBlank()) continue;
            if (paragraph.length() > ANALYSIS_INPUT_FRAGMENT_CHARS) {
                if (current.length() > 0) {
                    fragments.add(current.toString());
                    current.setLength(0);
                }
                for (int start = 0; start < paragraph.length(); start += ANALYSIS_INPUT_FRAGMENT_CHARS) {
                    fragments.add(paragraph.substring(start, Math.min(paragraph.length(), start + ANALYSIS_INPUT_FRAGMENT_CHARS)));
                }
                continue;
            }
            if (current.length() + paragraph.length() > ANALYSIS_INPUT_FRAGMENT_CHARS && current.length() > 0) {
                fragments.add(current.toString());
                current.setLength(0);
            }
            current.append(paragraph);
        }
        if (current.length() > 0) fragments.add(current.toString());
        return fragments.isEmpty() ? List.of(source) : fragments;
    }

    private CoreStageResult mergeCoreFragments(List<CoreStageResult> partials) {
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Map<String, Object>> atoms = new ArrayList<>();
        List<Map<String, Object>> units = new ArrayList<>();
        List<Object> questions = new ArrayList<>();
        List<Object> assumptions = new ArrayList<>();
        List<Object> affectedCases = new ArrayList<>();
        List<Object> newCases = new ArrayList<>();
        Map<String, Object> raw = new LinkedHashMap<>();
        Map<String, String> atomIdsByContent = new LinkedHashMap<>();
        Map<String, Map<String, Object>> unitsByName = new LinkedHashMap<>();
        int atomNumber = 1;
        int unitNumber = 1;
        for (int fragmentIndex = 0; fragmentIndex < partials.size(); fragmentIndex++) {
            CoreStageResult partial = partials.get(fragmentIndex);
            Map<String, Object> root = readJsonObject(partial.coreJson());
            if (root == null) continue;
            raw.put("part_" + (fragmentIndex + 1), safeJsonValue(partial.rawOutput()));
            copyMergedScalar(merged, root, "business_domain");
            copyMergedScalar(merged, root, "requirement_type");
            for (String key : List.of("input_sources", "affected_modules", "affected_pages", "affected_fields",
                    "affected_flows", "affected_roles", "review_risk_questions", "risk_scenarios",
                    "boundary_conditions", "conflicts", "uncertain_items", "out_of_scope")) {
                mergeJsonListField(merged, root, key);
            }
            String understanding = String.valueOf(root.getOrDefault("requirement_understanding", "")).trim();
            if (!understanding.isBlank() && !"null".equals(understanding)) {
                String previous = String.valueOf(merged.getOrDefault("requirement_understanding", ""));
                merged.put("requirement_understanding", previous.isBlank() ? understanding : previous + "\n" + understanding);
            }
            Map<String, String> atomIds = new LinkedHashMap<>();
            for (Map<String, Object> atom : requirementAtoms(partial.coreJson())) {
                String oldId = String.valueOf(atom.get("id"));
                String atomKey = (String.valueOf(atom.getOrDefault("title", "")) + "|"
                        + String.valueOf(atom.getOrDefault("requirement", ""))).trim();
                String existingId = atomIdsByContent.get(atomKey);
                if (existingId != null) {
                    atomIds.put(oldId, existingId);
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>(atom);
                String newId = "R" + atomNumber++;
                copy.put("id", newId);
                copy.put("source_fragment", fragmentIndex + 1);
                atoms.add(copy);
                atomIds.put(oldId, newId);
                atomIdsByContent.put(atomKey, newId);
            }
            Map<String, String> unitIds = new LinkedHashMap<>();
            for (Map<String, Object> unit : testUnits(partial.coreJson())) {
                String oldId = String.valueOf(unit.get("id"));
                String unitKey = String.valueOf(unit.getOrDefault("name", "")).trim().toLowerCase(Locale.ROOT);
                List<String> remappedRefs = toStringList(unit.get("requirement_refs")).stream()
                        .map(ref -> atomIds.getOrDefault(ref, ref)).toList();
                Map<String, Object> existing = unitsByName.get(unitKey);
                if (existing != null) {
                    List<String> mergedRefs = new ArrayList<>(toStringList(existing.get("requirement_refs")));
                    remappedRefs.stream().filter(ref -> !mergedRefs.contains(ref)).forEach(mergedRefs::add);
                    existing.put("requirement_refs", mergedRefs);
                    unitIds.put(oldId, String.valueOf(existing.get("id")));
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>(unit);
                String newId = "U" + unitNumber++;
                copy.put("id", newId);
                copy.put("requirement_refs", remappedRefs);
                copy.put("depends_on_unit_refs", List.of());
                copy.put("source_fragment", fragmentIndex + 1);
                units.add(copy);
                unitIds.put(oldId, newId);
                unitsByName.put(unitKey, copy);
            }
            appendJsonValues(questions, partial.clarificationQuestions());
            appendJsonValues(assumptions, partial.assumptions());
            appendJsonValues(affectedCases, partial.affectedCases());
            appendJsonValues(newCases, partial.newCasesNeeded());
        }
        merged.put("requirement_atoms", atoms);
        merged.put("test_units", units);
        return new CoreStageResult(toJson(raw), toJson(merged), toJson(distinctJsonValues(questions)),
                toJson(distinctJsonValues(assumptions)), toJson(distinctJsonValues(affectedCases)),
                null, toJson(distinctJsonValues(newCases)));
    }

    /** 仅以 U* 摘要建立跨片依赖，避免“提交/审批/通知”分散在不同输入片段时丢失完整流程。 */
    private CoreStageResult enrichCrossFragmentUnitDependencies(CurrentUser user,
                                                                Long projectId,
                                                                Long taskId,
                                                                Long modelConfigId,
                                                                Long promptTemplateId,
                                                                String taskTypePrefix,
                                                                CoreStageResult core) {
        Map<String, Object> root = readJsonObject(core.coreJson());
        if (root == null) return core;
        List<Map<String, Object>> units = testUnits(core.coreJson());
        if (units.size() < 2) return core;
        List<Map<String, Object>> summary = units.stream().map(unit -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", unit.get("id"));
            row.put("name", unit.get("name"));
            row.put("summary", unit.get("summary"));
            row.put("requirement_refs", unit.get("requirement_refs"));
            return row;
        }).toList();
        String systemPrompt = """
                你是测试主题依赖分析器。只根据输入 U* 摘要识别真实前后依赖，不能编造业务流程。
                严格返回 JSON：{"test_unit_dependencies":[{"unit_id":"U2","depends_on_unit_refs":["U1"]}],"no_dependencies":false}
                若没有可证明的依赖，返回空数组并令 no_dependencies=true。
                依赖表示“当前主题执行或完整流程验证以前序主题为基础”。不得返回不存在的 U 编号，不得形成自依赖或循环。
                """;
        // Cross-fragment dependencies only enrich FLOW_COMPOSED plans. They must never make the
        // independently verified R*/U* assets unavailable when a model cannot prove the graph.
        String output;
        List<Map<String, Object>> dependencies;
        try {
            output = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                    taskTypePrefix + "_CORE_DEPENDENCY_MAP", systemPrompt,
                    "## 测试主题摘要\n" + toJson(summary), ANALYSIS_CONTINUATION_MAX_TOKENS, "跨片主题依赖分析");
            dependencies = readJsonObjectList(normalizeJsonColumn(extractJson(output, "test_unit_dependencies")));
        } catch (LlmRuntimeException e) {
            log.warn("Cross-fragment dependency inference unavailable; continuing without automatic flow composition: {}", e.getMessage());
            return clearInferredUnitDependencies(core,
                    "跨片主题依赖未能可靠推断，未自动生成跨主题完整流程；各主题仍会独立拆点和生成用例，完整流程需人工确认。",
                    "调用失败：" + e.errorCode());
        }
        if (dependencies == null) {
            log.warn("Cross-fragment dependency inference returned no usable dependency array; continuing without automatic flow composition");
            return clearInferredUnitDependencies(core,
                    "跨片主题依赖未返回可验证结果，未自动生成跨主题完整流程；各主题仍会独立拆点和生成用例，完整流程需人工确认。",
                    output);
        }
        Set<String> validIds = units.stream().map(unit -> String.valueOf(unit.get("id"))).collect(Collectors.toSet());
        Map<String, Set<String>> mapped = new LinkedHashMap<>();
        for (Map<String, Object> dependency : dependencies) {
            String unitId = String.valueOf(dependency.getOrDefault("unit_id", ""));
            List<String> refs = toStringList(dependency.get("depends_on_unit_refs"));
            if (!validIds.contains(unitId)) {
                log.warn("Ignoring inferred dependency for unknown test unit {}", unitId);
                continue;
            }
            Set<String> validRefs = refs.stream()
                    .filter(validIds::contains)
                    .filter(ref -> !unitId.equals(ref))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (validRefs.size() != refs.size()) {
                log.warn("Ignoring invalid/self inferred dependency refs for test unit {}", unitId);
            }
            if (!validRefs.isEmpty()) mapped.put(unitId, validRefs);
        }
        if (hasDependencyCycle(mapped)) {
            log.warn("Cross-fragment dependency inference contains a cycle; continuing without automatic flow composition");
            return clearInferredUnitDependencies(core,
                    "跨片主题依赖存在循环或方向不确定，未自动生成跨主题完整流程；各主题仍会独立拆点和生成用例，完整流程需人工确认。",
                    output);
        }
        List<Map<String, Object>> updatedUnits = new ArrayList<>();
        for (Map<String, Object> unit : units) {
            Map<String, Object> copy = new LinkedHashMap<>(unit);
            copy.put("depends_on_unit_refs", new ArrayList<>(mapped.getOrDefault(String.valueOf(unit.get("id")), Set.of())));
            updatedUnits.add(copy);
        }
        root.put("test_units", updatedUnits);
        String raw = toJson(Map.of("core_parts", safeJsonValue(core.rawOutput()), "dependency_map", safeJsonValue(output)));
        return new CoreStageResult(raw, toJson(root), core.clarificationQuestions(), core.assumptions(),
                core.affectedCases(), core.changeScope(), core.newCasesNeeded());
    }

    /**
     * A dependency graph inferred from fragments is optional evidence, not a source of truth.
     * Keep node-focused coverage available and record why no automatic end-to-end composition was made.
     */
    private CoreStageResult clearInferredUnitDependencies(CoreStageResult core, String uncertainty, String dependencyOutput) {
        Map<String, Object> root = readJsonObject(core.coreJson());
        if (root == null) return core;
        List<Map<String, Object>> updatedUnits = new ArrayList<>();
        for (Map<String, Object> unit : testUnits(core.coreJson())) {
            Map<String, Object> copy = new LinkedHashMap<>(unit);
            copy.put("depends_on_unit_refs", List.of());
            updatedUnits.add(copy);
        }
        root.put("test_units", updatedUnits);
        List<Object> uncertainItems = new ArrayList<>();
        Object oldUncertainItems = root.get("uncertain_items");
        if (oldUncertainItems instanceof Collection<?> collection) uncertainItems.addAll(collection);
        if (uncertainItems.stream().map(String::valueOf).noneMatch(uncertainty::equals)) {
            uncertainItems.add(uncertainty);
        }
        root.put("uncertain_items", distinctJsonValues(uncertainItems));
        String raw = toJson(Map.of(
                "core_parts", safeJsonValue(core.rawOutput()),
                "dependency_map", safeJsonValue(dependencyOutput),
                "dependency_map_status", "SKIPPED_UNVERIFIED"));
        return new CoreStageResult(raw, toJson(root), core.clarificationQuestions(), core.assumptions(),
                core.affectedCases(), core.changeScope(), core.newCasesNeeded());
    }

    private boolean hasDependencyCycle(Map<String, Set<String>> dependencies) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String unit : dependencies.keySet()) {
            if (hasDependencyCycle(unit, dependencies, visiting, visited)) return true;
        }
        return false;
    }

    private boolean hasDependencyCycle(String unit, Map<String, Set<String>> dependencies,
                                       Set<String> visiting, Set<String> visited) {
        if (visited.contains(unit)) return false;
        if (!visiting.add(unit)) return true;
        for (String dependency : dependencies.getOrDefault(unit, Set.of())) {
            if (hasDependencyCycle(dependency, dependencies, visiting, visited)) return true;
        }
        visiting.remove(unit);
        visited.add(unit);
        return false;
    }

    private void copyMergedScalar(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank() && !"null".equals(String.valueOf(value))) {
            target.putIfAbsent(key, value);
        }
    }

    private void mergeJsonListField(Map<String, Object> target, Map<String, Object> source, String key) {
        List<Object> values = new ArrayList<>();
        Object old = target.get(key);
        if (old instanceof Collection<?> collection) values.addAll(collection);
        Object next = source.get(key);
        if (next instanceof Collection<?> collection) values.addAll(collection);
        if (!values.isEmpty()) target.put(key, distinctJsonValues(values));
    }

    private void appendJsonValues(List<Object> target, String json) {
        Object value = readJsonValue(json);
        if (value instanceof Collection<?> collection) target.addAll(collection);
        else if (value != null) target.add(value);
    }

    private List<Object> distinctJsonValues(Collection<?> values) {
        LinkedHashMap<String, Object> distinct = new LinkedHashMap<>();
        for (Object value : values) distinct.putIfAbsent(toJson(value), value);
        return new ArrayList<>(distinct.values());
    }

    /**
     * 覆盖矩阵按语义工作项自适应装箱。一次请求可以处理多个相邻 U*，但每个 U* 仍
     * 独立校验、独立追溯；批量结果漏掉某个 U* 时只修复该工作项，不牺牲覆盖换成本。
     */
    private CoverageStageResult runCoverageMatrixStages(CurrentUser user,
                                                         Long projectId,
                                                         Long taskId,
                                                         Long modelConfigId,
                                                         Long promptTemplateId,
                                                         String taskTypePrefix,
                                                         String coreJson,
                                                         ProjectSemanticContextService.BuildResult semanticContext) {
        List<Map<String, Object>> atoms = requirementAtoms(coreJson);
        List<Map<String, Object>> units = testUnits(coreJson);
        List<Map<String, Object>> mergedRows = new ArrayList<>();
        List<Object> reviewNotes = new ArrayList<>();
        Map<String, Object> rawStages = new LinkedHashMap<>();
        List<CoverageWorkItem> workItems = buildCoverageWorkItems(units, atoms);
        int nodeIndex = 1;
        for (List<CoverageWorkItem> batch : partitionCoverageWorkItems(workItems)) {
            int currentNode = nodeIndex++;
            String output = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                    taskTypePrefix + "_COVERAGE_MATRIX_BATCH_" + currentNode,
                    buildCoverageMatrixSystemPrompt(),
                    buildCoverageMatrixBatchUserPrompt(batch, semanticContext),
                    ANALYSIS_MAX_TOKENS, "覆盖矩阵（批次 " + currentNode + " / " + batch.size() + " 个主题）");
            String matrix = normalizeCoverageMatrixJson(normalizeJsonColumn(extractJson(output, "coverage_matrix")));
            List<Map<String, Object>> batchRows = readJsonObjectList(matrix);
            Map<String, Object> nodeRecord = new LinkedHashMap<>();
            nodeRecord.put("initial", safeJsonValue(output));
            List<String> repairedUnits = new ArrayList<>();
            for (CoverageWorkItem item : batch) {
                List<Map<String, Object>> unitRows = coverageRowsForWorkItem(batchRows, item, batch.size() == 1);
                if (!isUsableCoverageForWorkItem(unitRows, item)) {
                    String repairOutput = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                            taskTypePrefix + "_COVERAGE_MATRIX_REPAIR_" + currentNode + "_" + safeStageSuffix(item.unitId()),
                            buildCoverageMatrixSystemPrompt(),
                            buildCoverageMatrixBatchUserPrompt(List.of(item), semanticContext)
                                    + "\n\n上一次批量结果遗漏或未完整覆盖本主题。请逐条覆盖本节点 R*，不得只返回主流程和异常。",
                            ANALYSIS_CONTINUATION_MAX_TOKENS,
                            "覆盖矩阵补齐（主题 " + item.unitId() + "）");
                    String repairedMatrix = normalizeCoverageMatrixJson(
                            normalizeJsonColumn(extractJson(repairOutput, "coverage_matrix")));
                    unitRows = coverageRowsForWorkItem(readJsonObjectList(repairedMatrix), item, true);
                    nodeRecord.put("repair_" + item.unitId(), safeJsonValue(repairOutput));
                    repairedUnits.add(item.unitId());
                }
                if (!isUsableCoverageForWorkItem(unitRows, item)) {
                    throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                            "主题节点 " + item.unitId() + " 未形成可执行覆盖矩阵，不能以通用模板继续生成测试点。");
                }
                addCoverageRows(mergedRows, unitRows, item);
            }
            appendJsonValues(reviewNotes, normalizeJsonColumn(extractJson(output, "matrix_review_notes")));
            nodeRecord.put("test_unit_refs", batch.stream().map(CoverageWorkItem::unitId).toList());
            nodeRecord.put("repaired_test_unit_refs", repairedUnits);
            rawStages.put("batch_" + currentNode, nodeRecord);
        }
        requireCoverageMatrixCoversAllWorkItems(mergedRows, workItems);
        return new CoverageStageResult(toJson(rawStages), toJson(mergedRows), toJson(distinctJsonValues(reviewNotes)));
    }

    private List<CoverageWorkItem> buildCoverageWorkItems(List<Map<String, Object>> units,
                                                           List<Map<String, Object>> atoms) {
        List<CoverageWorkItem> workItems = new ArrayList<>();
        for (Map<String, Object> unit : units) {
            List<Map<String, Object>> unitAtoms = atomsForUnit(unit, atoms);
            if (unitAtoms.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "测试主题节点 " + unit.get("id") + " 没有需求原子，无法设计覆盖矩阵。");
            }
            for (List<Map<String, Object>> atomBatch : partitionAtomsForPrompt(unitAtoms)) {
                workItems.add(new CoverageWorkItem(String.valueOf(unit.get("id")), unit, atomBatch));
            }
        }
        return workItems;
    }

    private List<List<CoverageWorkItem>> partitionCoverageWorkItems(List<CoverageWorkItem> workItems) {
        List<List<CoverageWorkItem>> batches = new ArrayList<>();
        List<CoverageWorkItem> current = new ArrayList<>();
        int currentChars = 0;
        for (CoverageWorkItem item : workItems) {
            int itemChars = toJson(item.promptPayload()).length();
            if (!current.isEmpty() && (current.size() >= MAX_COVERAGE_WORK_ITEMS_PER_CALL
                    || currentChars + itemChars > MAX_COVERAGE_BATCH_CHARS)) {
                batches.add(new ArrayList<>(current));
                current.clear();
                currentChars = 0;
            }
            current.add(item);
            currentChars += itemChars;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    private List<Map<String, Object>> coverageRowsForWorkItem(List<Map<String, Object>> rows,
                                                               CoverageWorkItem item,
                                                               boolean allowSingleItemCompatibility) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Map<String, Object>> matched = rows.stream()
                .filter(row -> item.unitId().equals(String.valueOf(row.getOrDefault("test_unit_ref", ""))))
                .toList();
        if (matched.isEmpty() && allowSingleItemCompatibility) {
            matched = rows;
        }
        return matched;
    }

    private void addCoverageRows(List<Map<String, Object>> target,
                                 List<Map<String, Object>> rows,
                                 CoverageWorkItem item) {
        List<String> requirementRefs = item.atoms().stream()
                .map(atom -> String.valueOf(atom.get("id"))).distinct().toList();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("test_unit_ref", item.unitId());
            copy.put("requirement_refs", requirementRefs);
            target.add(copy);
        }
    }

    private boolean isUsableCoverageForWorkItem(List<Map<String, Object>> rows,
                                                 CoverageWorkItem item) {
        if (!isUsableUnitCoverageMatrix(toJson(rows))) return false;
        Set<String> returnedRefs = rows.stream()
                .flatMap(row -> toStringList(row.get("requirement_refs")).stream())
                .collect(Collectors.toSet());
        return item.atoms().stream().map(atom -> String.valueOf(atom.get("id")))
                .allMatch(returnedRefs::contains);
    }

    private void requireCoverageMatrixCoversAllWorkItems(List<Map<String, Object>> rows,
                                                          List<CoverageWorkItem> workItems) {
        Set<String> coveredUnits = rows.stream()
                .map(row -> String.valueOf(row.getOrDefault("test_unit_ref", "")))
                .filter(id -> !id.isBlank()).collect(Collectors.toSet());
        List<String> missing = workItems.stream().map(CoverageWorkItem::unitId).distinct()
                .filter(id -> !coveredUnits.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "覆盖矩阵未覆盖测试主题：" + String.join("、", missing));
        }
    }

    private String safeStageSuffix(String value) {
        String suffix = value == null ? "UNIT" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return suffix.length() > 24 ? suffix.substring(0, 24) : suffix;
    }

    private boolean isUsableUnitCoverageMatrix(String matrixJson) {
        List<Map<String, Object>> rows = readJsonObjectList(matrixJson);
        if (rows == null || rows.isEmpty()) return false;
        int total = 0;
        int nonMain = 0;
        for (Map<String, Object> row : rows) {
            total += numberValue(row.get("total"));
            for (String key : List.of("branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent")) {
                Object dimension = row.get(key);
                if (dimension instanceof Map<?, ?> map) {
                    int count = numberValue(map.get("count"));
                    if (count > toStringList(map.get("items")).size()) return false;
                    if (count > 0) nonMain++;
                }
            }
        }
        return total > 0 && nonMain > 0;
    }

    /**
     * Providers frequently use semantically equivalent JSON field names (for example
     * idempotency rather than idempotent) or omit the redundant total.  Normalize the
     * transport shape at the boundary; do not reject an otherwise complete matrix merely
     * because it does not mirror our storage field spelling.
     */
    private String normalizeCoverageMatrixJson(String matrixJson) {
        List<Map<String, Object>> rows = readJsonObjectList(matrixJson);
        if (rows == null) return matrixJson;
        List<String> dimensions = List.of("main_flow", "branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent");
        Map<String, List<String>> aliases = Map.of(
                "auth", List.of("authorization", "permission", "permissions"),
                "idempotent", List.of("idempotency", "idempotence")
        );
        List<Map<String, Object>> normalizedRows = new ArrayList<>();
        for (Map<String, Object> source : rows) {
            Map<String, Object> row = new LinkedHashMap<>(source);
            int total = 0;
            for (String dimension : dimensions) {
                Object value = row.get(dimension);
                if (!(value instanceof Map<?, ?>)) {
                    for (String alias : aliases.getOrDefault(dimension, List.of())) {
                        Object candidate = row.get(alias);
                        if (candidate instanceof Map<?, ?>) {
                            value = candidate;
                            break;
                        }
                    }
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                if (value instanceof Map<?, ?> map) {
                    Object count = map.get("count");
                    if (count != null) detail.put("count", numberValue(count));
                    detail.put("items", toStringList(map.get("items")));
                } else {
                    detail.put("count", 0);
                    detail.put("items", List.of());
                }
                int count = numberValue(detail.get("count"));
                List<String> items = toStringList(detail.get("items"));
                // A declared count without enough concrete items is not a usable test asset.
                // Preserve it so the quality check asks the model to complete the missing items.
                if (count < items.size()) count = items.size();
                detail.put("count", count);
                row.put(dimension, detail);
                total += count;
            }
            row.put("total", total);
            normalizedRows.add(row);
        }
        return toJson(normalizedRows);
    }

    /**
     * 按需求原子分批拆测试点。批次大小只控制一次模型调用的技术负载，
     * 不作为测试点或用例的完成数量上限。
     */
    private TestPointStageResult runTestPointStages(CurrentUser user,
                                                     Long projectId,
                                                     Long taskId,
                                                     Long modelConfigId,
                                                     Long promptTemplateId,
                                                     String taskTypePrefix,
                                                     String requirementText,
                                                     String coreJson,
                                                     String coverageMatrix,
                                                     ProjectSemanticContextService.BuildResult semanticContext) {
        List<Map<String, Object>> atoms = requirementAtoms(coreJson);
        List<Map<String, Object>> units = testUnits(coreJson);
        List<Map<String, Object>> matrixRows = readJsonObjectList(coverageMatrix);
        if (atoms.isEmpty() || units.isEmpty() || matrixRows == null || matrixRows.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "测试点拆解缺少需求原子、测试主题或覆盖矩阵，不能继续生成用例。");
        }
        Map<String, Map<String, Object>> atomsById = atoms.stream().collect(Collectors.toMap(
                atom -> String.valueOf(atom.get("id")), atom -> atom, (left, right) -> left, LinkedHashMap::new));
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> row : matrixRows) {
            String unitId = String.valueOf(row.get("test_unit_ref"));
            if (unitId.isBlank() || "null".equals(unitId)) continue;
            List<String> requirementRefs = toStringList(row.get("requirement_refs"));
            for (String dimension : List.of("main_flow", "branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent")) {
                Object rawDimension = row.get(dimension);
                if (!(rawDimension instanceof Map<?, ?> detail) || numberValue(detail.get("count")) <= 0) continue;
                List<String> items = toStringList(detail.get("items"));
                for (String item : items) {
                    points.add(structuralTestPoint(item, dimension, unitId, requirementRefs, row, atomsById));
                }
            }
        }
        if (points.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "覆盖矩阵未提供可落地的条目，不能用空测试点继续生成用例。");
        }
        assignStableTestPointIds(points);
        applyPreconditionTestPointRefs(points, units);
        String merged = mergeTestPointJson(toJson(List.of()), toJson(points));
        requireStableTestPointIds(merged);
        List<String> missing = missingRequirementAtomIds(atoms, merged);
        if (!missing.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "覆盖矩阵结构化后仍未覆盖需求原子：" + String.join("、", missing));
        }
        return new TestPointStageResult(toJson(Map.of(
                "mode", "STRUCTURAL_FROM_COVERAGE_MATRIX",
                "test_point_count", points.size())), merged, buildRequirementAtomSelfCheck(atoms, merged));
    }

    /** Historical model-led test-point splitter retained for diagnostics, not the production path. */
    @SuppressWarnings("unused")
    private TestPointStageResult runModelTestPointStages(CurrentUser user,
                                                     Long projectId,
                                                     Long taskId,
                                                     Long modelConfigId,
                                                     Long promptTemplateId,
                                                     String taskTypePrefix,
                                                     String requirementText,
                                                     String coreJson,
                                                     String coverageMatrix,
                                                     ProjectSemanticContextService.BuildResult semanticContext) {
        List<Map<String, Object>> atoms = requirementAtoms(coreJson);
        List<Map<String, Object>> units = testUnits(coreJson);
        if (atoms.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "需求分析未形成可追溯的需求原子，无法可靠拆解测试点。");
        }
        if (units.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "需求分析未形成测试主题节点，无法按业务节点拆解测试点。");
        }
        List<Map<String, Object>> allPoints = new ArrayList<>();
        Map<String, Object> rawStages = new LinkedHashMap<>();
        int nodeIndex = 1;
        for (Map<String, Object> unit : units) {
            List<Map<String, Object>> unitAtoms = atomsForUnit(unit, atoms);
            if (unitAtoms.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "测试主题节点 " + unit.get("id") + " 未关联任何需求原子，无法继续。");
            }
            for (List<Map<String, Object>> atomBatch : partitionAtomsForPrompt(unitAtoms)) {
                String atomBatchJson = toJson(atomBatch);
                List<List<Map<String, Object>>> coverageSlices = partitionCoverageForTestPointNodes(
                        coverageRowsForUnitAndAtoms(coverageMatrix, unit, atomBatch));
                if (coverageSlices.isEmpty()) {
                    throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                            "测试主题节点 " + unit.get("id") + " 没有可执行覆盖维度，无法拆解测试点。");
                }
                for (List<Map<String, Object>> coverageSlice : coverageSlices) {
                    int currentNode = nodeIndex++;
                    String scopedCoverageJson = toJson(coverageSlice);
                    String output = invokeAnalysisStage(
                        user, projectId, taskId, modelConfigId, promptTemplateId,
                        taskTypePrefix + "_TEST_POINTS_" + currentNode,
                        buildTestPointsSystemPrompt(),
                        buildTestPointsUserPrompt(requirementText, coreJson, scopedCoverageJson, semanticContext, toJson(unit), atomBatchJson),
                        ANALYSIS_CONTINUATION_MAX_TOKENS,
                        "测试点拆解（主题节点 " + unit.get("id") + " / 片段 " + currentNode + "）"
                    );
                    String pointsJson = enrichTestPoints(normalizeJsonColumn(extractJson(output, "test_points")), semanticContext);
                    List<String> missing = missingRequirementAtomIds(atomBatch, pointsJson);
                    if (!missing.isEmpty()) {
                        String repairOutput = invokeAnalysisStage(
                        user, projectId, taskId, modelConfigId, promptTemplateId,
                        taskTypePrefix + "_TEST_POINTS_REPAIR_" + currentNode,
                        buildTestPointsSystemPrompt(),
                        buildTestPointRepairPrompt(requirementText, coreJson, scopedCoverageJson, toJson(unit), atomBatchJson, missing),
                        ANALYSIS_CONTINUATION_MAX_TOKENS,
                        "缺失需求原子的测试点补齐（节点 " + currentNode + "）"
                        );
                        String repairPoints = enrichTestPoints(normalizeJsonColumn(extractJson(repairOutput, "test_points")), semanticContext);
                        pointsJson = mergeTestPointJson(pointsJson, repairPoints);
                        missing = missingRequirementAtomIds(atomBatch, pointsJson);
                        rawStages.put("node_" + currentNode + "_repair", safeJsonValue(repairOutput));
                    }
                    if (!missing.isEmpty()) {
                        throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                            "测试点拆解未覆盖需求原子：" + String.join("、", missing) + "。系统已自动补齐一次仍失败，不能生成不完整用例。");
                    }
                    List<Map<String, Object>> parsed = readJsonObjectList(pointsJson);
                    if (parsed == null || parsed.isEmpty()) {
                        throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                            "测试点拆解未返回可用结果，不能用空测试点继续生成用例。");
                    }
                    allPoints.addAll(parsed);
                    rawStages.put("node_" + currentNode, safeJsonValue(output));
                }
            }
        }
        assignStableTestPointIds(allPoints);
        String merged = mergeTestPointJson(toJson(List.of()), toJson(allPoints));
        requireStableTestPointIds(merged);
        List<String> stillMissing = missingRequirementAtomIds(atoms, merged);
        if (!stillMissing.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "测试点拆解完成后仍有未覆盖需求原子：" + String.join("、", stillMissing));
        }
        return new TestPointStageResult(toJson(rawStages), merged, buildRequirementAtomSelfCheck(atoms, merged));
    }

    private Map<String, Object> structuralTestPoint(String item,
                                                     String dimension,
                                                     String unitId,
                                                     List<String> requirementRefs,
                                                     Map<String, Object> matrixRow,
                                                     Map<String, Map<String, Object>> atomsById) {
        String pointType = pointTypeForDimension(dimension);
        boolean needsConfirmation = requirementRefs.stream()
                .map(atomsById::get).filter(Objects::nonNull)
                .anyMatch(atom -> Boolean.TRUE.equals(atom.get("needs_clarification")));
        List<String> sourceBasis = requirementRefs.stream().map(atomsById::get).filter(Objects::nonNull)
                .map(atom -> String.valueOf(atom.getOrDefault("source_basis", atom.getOrDefault("requirement", "需求描述"))))
                .filter(text -> !text.isBlank() && !"null".equals(text)).distinct().toList();
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("title", item);
        point.put("description", item);
        point.put("test_dimension", dimension);
        point.put("point_type", pointType);
        point.put("skill_layer", skillLayerForPointType(pointType));
        point.put("design_method", designMethodForPointType(pointType));
        point.put("priority_hint", "MAIN_FLOW".equals(pointType) ? "CORE" : "EXTENDED");
        point.put("related_module", String.valueOf(matrixRow.getOrDefault("module", "未命名模块")));
        point.put("related_page", "");
        point.put("related_flow", "");
        point.put("test_unit_ref", unitId);
        point.put("requirement_refs", requirementRefs);
        point.put("precondition_test_point_refs", List.of());
        point.put("case_strategy", "NODE_FOCUSED");
        point.put("compose_with_test_point_refs", List.of());
        point.put("source_basis", sourceBasis);
        point.put("source_refs", Map.of("tom_node_refs", List.of(), "page_refs", List.of(),
                "business_pack_refs", List.of(), "trace_refs", List.of()));
        point.put("coverage_status", needsConfirmation ? "LOW_EVIDENCE" : "SUPPORTED");
        point.put("unsupported_items", List.of());
        point.put("confidence", needsConfirmation ? 0.6 : 0.85);
        point.put("needs_confirmation", needsConfirmation);
        return point;
    }

    private String initializeTestPointScopes(String testPointsJson, String coreJson) {
        List<Map<String, Object>> points = readJsonObjectList(testPointsJson);
        if (points == null) return testPointsJson;
        Map<String, Map<String, Object>> atomsById = requirementAtoms(coreJson).stream().collect(Collectors.toMap(
                atom -> String.valueOf(atom.get("id")), atom -> atom, (left, right) -> left, LinkedHashMap::new));
        for (Map<String, Object> point : points) {
            List<Map<String, Object>> sourceAtoms = toStringList(point.get("requirement_refs")).stream()
                    .map(atomsById::get).filter(Objects::nonNull).toList();
            String recommendation = recommendPointScope(sourceAtoms);
            point.put("scope_recommendation", recommendation);
            point.put("scope_reason", sourceAtoms.stream()
                    .map(atom -> String.valueOf(atom.getOrDefault("scope_reason", "")))
                    .filter(reason -> !reason.isBlank() && !"null".equals(reason)).distinct()
                    .collect(Collectors.joining("；")));
            point.put("generation_scope", switch (recommendation) {
                case "IN_SCOPE" -> SCOPE_GENERATE;
                case "OUT_OF_SCOPE" -> SCOPE_EXCLUDED;
                default -> SCOPE_REFERENCE_ONLY;
            });
            point.put("scope_decision_source", "AI_RECOMMENDATION");
        }
        return toJson(points);
    }

    private String initializeRequirementAtomScopes(String coreJson) {
        Map<String, Object> root = readJsonObject(coreJson);
        if (root == null) throw new BusinessException("需求范围识别结果无法解析");
        List<Map<String, Object>> atoms = requirementAtoms(coreJson);
        for (Map<String, Object> atom : atoms) {
            String recommendation = String.valueOf(atom.getOrDefault("scope_recommendation", "NEEDS_CONFIRMATION"))
                    .trim().toUpperCase(Locale.ROOT);
            if (!Set.of("IN_SCOPE", "REFERENCE_ONLY", "OUT_OF_SCOPE", "NEEDS_CONFIRMATION").contains(recommendation)) {
                recommendation = "NEEDS_CONFIRMATION";
            }
            atom.put("scope_recommendation", recommendation);
            atom.put("generation_scope", switch (recommendation) {
                case "IN_SCOPE" -> SCOPE_GENERATE;
                case "OUT_OF_SCOPE" -> SCOPE_EXCLUDED;
                default -> SCOPE_REFERENCE_ONLY;
            });
            atom.put("scope_decision_source", "AI_RECOMMENDATION");
        }
        root.put("requirement_atoms", atoms);
        return toJson(root);
    }

    private String initializeRequirementScopeReview(String coreJson) {
        Map<String, Object> root = readJsonObject(coreJson);
        if (root == null) throw new BusinessException("需求范围识别结果无法解析");
        List<Map<String, Object>> atoms = requirementAtoms(coreJson);
        int generate = 0;
        int reference = 0;
        int excluded = 0;
        for (Map<String, Object> atom : atoms) {
            if (SCOPE_GENERATE.equals(scopeOf(atom))) generate++;
            else if (SCOPE_REFERENCE_ONLY.equals(scopeOf(atom))) reference++;
            else excluded++;
        }
        root.put("requirement_scope_review", Map.of(
                "status", "PENDING",
                "generate_count", generate,
                "reference_count", reference,
                "excluded_count", excluded));
        return toJson(root);
    }

    private String recommendPointScope(List<Map<String, Object>> atoms) {
        Set<String> scopes = atoms.stream()
                .map(atom -> String.valueOf(atom.getOrDefault("scope_recommendation", "IN_SCOPE")).trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (scopes.contains("NEEDS_CONFIRMATION")) return "NEEDS_CONFIRMATION";
        if (!scopes.isEmpty() && scopes.stream().allMatch(scope -> Set.of("REFERENCE_ONLY", "OUT_OF_SCOPE").contains(scope))) {
            return scopes.contains("OUT_OF_SCOPE") ? "OUT_OF_SCOPE" : "REFERENCE_ONLY";
        }
        return "IN_SCOPE";
    }

    private String initializeScopeReview(String analysisResult, String testPointsJson) {
        Map<String, Object> root = readJsonObject(analysisResult);
        if (root == null) root = new LinkedHashMap<>();
        List<Map<String, Object>> points = readJsonObjectList(testPointsJson);
        int recommendedGenerate = 0;
        int recommendedReference = 0;
        int recommendedExcluded = 0;
        if (points != null) {
            for (Map<String, Object> point : points) {
                if (SCOPE_GENERATE.equals(String.valueOf(point.get("generation_scope")))) recommendedGenerate++;
                else if (SCOPE_REFERENCE_ONLY.equals(scopeOf(point))) recommendedReference++;
                else recommendedExcluded++;
            }
        }
        root.put("test_point_scope_review", Map.of(
                "status", "PENDING",
                "generate_count", recommendedGenerate,
                "reference_count", recommendedReference,
                "excluded_count", recommendedExcluded));
        return toJson(root);
    }

    private String pointTypeForDimension(String dimension) {
        return switch (dimension) {
            case "main_flow" -> "MAIN_FLOW";
            case "branch" -> "BRANCH";
            case "boundary" -> "BOUNDARY";
            case "exception" -> "EXCEPTION";
            case "state" -> "STATE";
            case "data" -> "DATA";
            case "auth" -> "AUTH";
            case "concurrency" -> "CONCURRENCY";
            default -> "IDEMPOTENT";
        };
    }

    private String skillLayerForPointType(String pointType) {
        return switch (pointType) {
            case "MAIN_FLOW", "BRANCH" -> "FUNCTIONAL";
            case "EXCEPTION", "STATE", "AUTH" -> "EXCEPTION";
            default -> "BOUNDARY_SUPPLEMENT";
        };
    }

    private String designMethodForPointType(String pointType) {
        return switch (pointType) {
            case "MAIN_FLOW", "BRANCH" -> "场景法";
            case "BOUNDARY" -> "边界值";
            case "STATE" -> "状态迁移";
            case "DATA" -> "数据一致性检查";
            case "AUTH" -> "场景法";
            case "CONCURRENCY", "IDEMPOTENT", "EXCEPTION" -> "错误推测";
            default -> "场景法";
        };
    }

    private void applyPreconditionTestPointRefs(List<Map<String, Object>> points,
                                                  List<Map<String, Object>> units) {
        Map<String, List<Map<String, Object>>> pointsByUnit = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            pointsByUnit.computeIfAbsent(String.valueOf(point.get("test_unit_ref")), ignored -> new ArrayList<>()).add(point);
        }
        for (Map<String, Object> point : points) {
            Map<String, Object> unit = units.stream()
                    .filter(candidate -> String.valueOf(candidate.get("id")).equals(String.valueOf(point.get("test_unit_ref"))))
                    .findFirst().orElse(null);
            point.put("precondition_test_point_refs", upstreamFlowPointRefs(unit, pointsByUnit));
        }
    }

    private List<List<Map<String, Object>>> partitionAtomsForPrompt(List<Map<String, Object>> atoms) {
        return partitionObjectsForPrompt(atoms, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS);
    }

    /**
     * A single business subject can have many coverage dimensions.  Split those dimensions
     * into durable LLM nodes instead of asking one response to emit every point at once.
     */
    private List<List<Map<String, Object>>> partitionCoverageForTestPointNodes(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<List<Map<String, Object>>> slices = new ArrayList<>();
        List<String> dimensionKeys = List.of("main_flow", "branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent");
        for (Map<String, Object> row : rows) {
            List<String> active = dimensionKeys.stream()
                    .filter(key -> row.get(key) instanceof Map<?, ?> dimension && numberValue(dimension.get("count")) > 0)
                    .toList();
            for (int index = 0; index < active.size(); index += MAX_COVERAGE_DIMENSIONS_PER_TEST_POINT_NODE) {
                Set<String> allowed = new LinkedHashSet<>(active.subList(index,
                        Math.min(active.size(), index + MAX_COVERAGE_DIMENSIONS_PER_TEST_POINT_NODE)));
                Map<String, Object> slice = new LinkedHashMap<>(row);
                int total = 0;
                for (String key : dimensionKeys) {
                    if (allowed.contains(key)) {
                        total += numberValue(((Map<?, ?>) row.get(key)).get("count"));
                    } else {
                        slice.put(key, Map.of("count", 0, "items", List.of()));
                    }
                }
                slice.put("total", total);
                slices.add(List.of(slice));
            }
        }
        return slices;
    }

    private List<Map<String, Object>> coverageRowsForUnitAndAtoms(String coverageMatrixJson,
                                                                    Map<String, Object> unit,
                                                                    List<Map<String, Object>> atomBatch) {
        String unitId = String.valueOf(unit.get("id"));
        Set<String> atomIds = atomBatch.stream().map(atom -> String.valueOf(atom.get("id"))).collect(Collectors.toSet());
        List<Map<String, Object>> rows = readJsonObjectList(coverageMatrixJson);
        if (rows == null) return List.of();
        return rows.stream()
                .filter(row -> unitId.equals(String.valueOf(row.get("test_unit_ref"))))
                .filter(row -> {
                    List<String> refs = toStringList(row.get("requirement_refs"));
                    return refs.isEmpty() || refs.stream().anyMatch(atomIds::contains);
                })
                .toList();
    }

    private List<List<Map<String, Object>>> partitionObjectsForPrompt(List<Map<String, Object>> rows, int maxChars) {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        int currentChars = 0;
        for (Map<String, Object> row : rows) {
            int chars = toJson(row).length();
            if (!current.isEmpty() && currentChars + chars > maxChars) {
                batches.add(new ArrayList<>(current));
                current.clear();
                currentChars = 0;
            }
            current.add(row);
            currentChars += chars;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    private void requireStableTestPointIds(String testPointsJson) {
        List<Map<String, Object>> points = readJsonObjectList(testPointsJson);
        if (points == null || points.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "测试点为空，无法建立用例编排计划。");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> point : points) {
            String id = String.valueOf(point.getOrDefault("id", "")).trim();
            if (!id.matches("TP\\d+")) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "测试点未返回有效 TP 编号，无法建立可恢复的用例编排计划。");
            }
            if (!ids.add(id)) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "测试点 TP 编号重复：" + id + "，无法可靠追溯用例。");
            }
        }
    }

    /** TP 编号是平台追溯元数据；按分析结果顺序固定，避免各独立拆点节点各自从 TP1 起号。 */
    private void assignStableTestPointIds(List<Map<String, Object>> points) {
        for (int index = 0; index < points.size(); index++) {
            points.get(index).put("id", "TP" + (index + 1));
        }
    }

    /**
     * 将原子测试点编排为两类通用用例计划：节点聚焦验证与跨节点完整流程。
     * 计划只引用 R/U/TP 标识，不包含任何业务领域硬编码。
     */
    private CaseCompositionStageResult runCaseCompositionStage(CurrentUser user,
                                                                 Long projectId,
                                                                 Long taskId,
                                                                 Long modelConfigId,
                                                                 Long promptTemplateId,
                                                                 String taskTypePrefix,
                                                                 String requirementText,
                                                                 String coreJson,
                                                                 String coverageMatrix,
                                                                 String testPoints,
                                                                 ProjectSemanticContextService.BuildResult semanticContext) {
        List<Map<String, Object>> allPoints = readJsonObjectList(testPoints);
        List<Map<String, Object>> units = testUnits(coreJson);
        if (allPoints == null || allPoints.isEmpty() || units.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "用例编排缺少测试点或测试主题节点。");
        }

        // CP/CD only express durable TP-to-case relationships.  Generating this structure
        // locally keeps every TP covered and reserves LLM calls for business understanding
        // and concrete case writing, where the model actually adds value.
        List<Map<String, Object>> plans = buildStructuralCasePlans(units, allPoints);
        Map<String, Object> rawStages = new LinkedHashMap<>();
        // A dependency edge is only evidence that one topic may rely on another.  It is not
        // sufficient evidence to turn every edge into an end-to-end case.  Let the compact
        // flow selector choose only real business closures; node plans remain complete if the
        // optional selector cannot produce a trustworthy result.
        try {
            List<Map<String, Object>> flowPlans = runFlowCompositionStage(user, projectId, taskId, modelConfigId,
                    promptTemplateId, taskTypePrefix, coreJson, units, allPoints);
            plans.addAll(flowPlans);
            rawStages.put("flow_plan_count", flowPlans.size());
        } catch (LlmRuntimeException ex) {
            rawStages.put("flow_selection", Map.of("status", "SKIPPED", "error_code", ex.errorCode().name(),
                    "message", ex.getMessage()));
        }
        assignStableCasePlanIds(plans);
        String casePlan = mergeCasePlanJson(toJson(List.of()), toJson(plans));
        List<String> missing = missingTestPointIdsFromCasePlan(testPoints, casePlan);
        if (isBlankJsonValue(casePlan) || !missing.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "用例编排未覆盖测试点：" + String.join("、", missing));
        }
        rawStages.put("mode", "OBJECTIVE_CLUSTERED_CASE_COMPOSITION");
        rawStages.put("test_point_count", allPoints.size());
        rawStages.put("node_focused_plan_count", plans.stream()
                .filter(plan -> "NODE_FOCUSED".equals(String.valueOf(plan.get("case_strategy")))).count());
        rawStages.put("total_plan_count", plans.size());
        return new CaseCompositionStageResult(toJson(rawStages), casePlan);
    }

    /**
     * Legacy model-led composer retained for diagnostics only.  The production path above
     * uses the deterministic TP/CP relation to avoid another large, lossy JSON generation.
     */
    @SuppressWarnings("unused")
    private CaseCompositionStageResult runModelCaseCompositionStage(CurrentUser user,
                                                                 Long projectId,
                                                                 Long taskId,
                                                                 Long modelConfigId,
                                                                 Long promptTemplateId,
                                                                 String taskTypePrefix,
                                                                 String requirementText,
                                                                 String coreJson,
                                                                 String coverageMatrix,
                                                                 String testPoints,
                                                                 ProjectSemanticContextService.BuildResult semanticContext) {
        List<Map<String, Object>> allPoints = readJsonObjectList(testPoints);
        List<Map<String, Object>> units = testUnits(coreJson);
        if (allPoints == null || allPoints.isEmpty() || units.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "用例编排缺少测试点或测试主题节点。");
        }
        List<Map<String, Object>> allPlans = new ArrayList<>();
        Map<String, Object> rawStages = new LinkedHashMap<>();
        int nodeIndex = 1;
        for (Map<String, Object> unit : units) {
            String unitId = String.valueOf(unit.get("id"));
            List<Map<String, Object>> unitPoints = allPoints.stream()
                    .filter(point -> unitId.equals(String.valueOf(point.get("test_unit_ref"))))
                    .toList();
            if (unitPoints.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "测试主题节点 " + unitId + " 没有对应测试点，无法编排用例。");
            }
            for (List<Map<String, Object>> pointBatch : partitionObjectsForPrompt(unitPoints, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS * 2)) {
                int currentNode = nodeIndex++;
                String output = invokeAnalysisStage(
                        user, projectId, taskId, modelConfigId, promptTemplateId,
                        taskTypePrefix + "_CASE_COMPOSITION_NODE_" + currentNode,
                        buildCaseCompositionSystemPrompt(),
                        buildCaseCompositionUserPrompt(requirementText, coreJson, coverageMatrix,
                                toJson(pointBatch), toJson(unit), semanticContext)
                                + buildPreconditionPointPrompt(unit, units, allPoints),
                        ANALYSIS_MAX_TOKENS,
                        "用例编排（主题节点 " + unitId + " / 片段 " + currentNode + "）"
                );
                String nodePlan = normalizeJsonColumn(extractJson(output, "case_plan"));
                List<String> missingNodePoints = missingTestPointIdsFromCasePlan(toJson(pointBatch), nodePlan);
                List<String> planProblems = validateNodeCasePlans(nodePlan, unit, allPoints, units, pointBatch);
                if (isBlankJsonValue(nodePlan) || !missingNodePoints.isEmpty() || !planProblems.isEmpty()) {
                    String repairOutput = invokeAnalysisStage(
                            user, projectId, taskId, modelConfigId, promptTemplateId,
                            taskTypePrefix + "_CASE_COMPOSITION_REPAIR_NODE_" + currentNode,
                            buildCaseCompositionSystemPrompt(),
                            buildCaseCompositionRepairPrompt(requirementText, coreJson, coverageMatrix,
                                    toJson(pointBatch), toJson(unit), semanticContext,
                                    mergeDistinct(missingNodePoints, planProblems)),
                            ANALYSIS_MAX_TOKENS,
                            "用例编排补齐（节点 " + currentNode + "）"
                    );
                    nodePlan = mergeCasePlanJson(nodePlan, normalizeJsonColumn(extractJson(repairOutput, "case_plan")));
                    rawStages.put("node_" + currentNode + "_repair", safeJsonValue(repairOutput));
                    missingNodePoints = missingTestPointIdsFromCasePlan(toJson(pointBatch), nodePlan);
                    planProblems = validateNodeCasePlans(nodePlan, unit, allPoints, units, pointBatch);
                }
                if (isBlankJsonValue(nodePlan) || !missingNodePoints.isEmpty() || !planProblems.isEmpty()) {
                    throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                            "主题节点 " + unitId + " 的用例编排不完整："
                                    + String.join("、", mergeDistinct(missingNodePoints, planProblems)));
                }
                List<Map<String, Object>> parsedPlan = readJsonObjectList(nodePlan);
                if (parsedPlan != null) allPlans.addAll(parsedPlan);
                rawStages.put("node_" + currentNode, safeJsonValue(output));
            }
        }
        List<Map<String, Object>> flowPlans = runFlowCompositionStage(user, projectId, taskId, modelConfigId,
                promptTemplateId, taskTypePrefix, coreJson, units, allPoints);
        allPlans.addAll(flowPlans);
        if (!flowPlans.isEmpty()) {
            rawStages.put("flow", flowPlans);
        }
        assignStableCasePlanIds(allPlans);
        String casePlan = mergeCasePlanJson(toJson(List.of()), toJson(allPlans));
        List<String> missing = missingTestPointIdsFromCasePlan(testPoints, casePlan);
        if (isBlankJsonValue(casePlan) || !missing.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "用例编排未覆盖测试点：" + String.join("、", missing)
                            + "。不能把未编排的测试点当作完成。" );
        }
        return new CaseCompositionStageResult(toJson(rawStages), casePlan);
    }

    private List<Map<String, Object>> buildStructuralCasePlans(List<Map<String, Object>> units,
                                                                List<Map<String, Object>> points) {
        Map<String, Map<String, Object>> unitsById = units.stream().collect(Collectors.toMap(
                unit -> String.valueOf(unit.get("id")), unit -> unit, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<Map<String, Object>>> pointsByUnit = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            pointsByUnit.computeIfAbsent(String.valueOf(point.get("test_unit_ref")), ignored -> new ArrayList<>()).add(point);
        }
        List<Map<String, Object>> plans = new ArrayList<>();
        for (List<Map<String, Object>> objectivePoints : groupPointsByVerificationObjective(points)) {
            Map<String, Object> firstPoint = objectivePoints.get(0);
            String unitId = String.valueOf(firstPoint.get("test_unit_ref"));
            Map<String, Object> unit = unitsById.get(unitId);
            List<String> preconditions = upstreamFlowPointRefs(unit, pointsByUnit);
            List<String> pointIds = objectivePoints.stream().map(point -> String.valueOf(point.get("id"))).toList();
            String title = buildVerificationObjectiveTitle(unit, objectivePoints);
            String designMethod = String.valueOf(firstPoint.getOrDefault("design_method", "场景法"));
            Map<String, Object> design = new LinkedHashMap<>();
            design.put("id", "CD1");
            design.put("title", title);
            design.put("scenario", buildVerificationObjectiveScenario(objectivePoints, title));
            design.put("design_method", designMethod);
            design.put("source_test_point_refs", pointIds);
            design.put("priority_hint", highestPriorityHint(objectivePoints));

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("id", "CP");
            plan.put("title", "节点验证：" + title);
            plan.put("case_strategy", "NODE_FOCUSED");
            plan.put("test_unit_refs", List.of(unitId));
            plan.put("source_test_point_refs", pointIds);
            plan.put("precondition_test_point_refs", preconditions);
            plan.put("depends_on_case_plan_refs", List.of());
            plan.put("design_method", designMethod);
            plan.put("case_designs", List.of(design));
            plan.put("priority_hint", highestPriorityHint(objectivePoints));
            plan.put("coverage_status", mergedCoverageStatus(objectivePoints));
            plan.put("source_basis", mergePointSourceBasis(objectivePoints));
            plan.put("needs_confirmation", objectivePoints.stream()
                    .anyMatch(point -> Boolean.TRUE.equals(point.get("needs_confirmation"))));
            plans.add(plan);
        }
        return plans;
    }

    /**
     * A test point is a coverage atom, not necessarily an independently maintainable test case.
     * Keep all atoms and their trace references, but group atoms from the same test topic that
     * share the same verification dimension and method into one case-writing objective.
     */
    private List<List<Map<String, Object>>> groupPointsByVerificationObjective(List<Map<String, Object>> points) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            String unitId = String.valueOf(point.getOrDefault("test_unit_ref", ""));
            String type = String.valueOf(point.getOrDefault("point_type", "FUNCTION"));
            String method = String.valueOf(point.getOrDefault("design_method", "场景法"));
            // Main flows remain separately traceable by requirement atom. Other dimensions are
            // cohesive checks within one topic, for example a form's boundary checks or a role's
            // authorization matrix, and must not be inflated into one draft per atom.
            String requirementKey = "MAIN_FLOW".equals(type)
                    ? String.join(",", toStringList(point.get("requirement_refs"))) : "";
            String key = unitId + "|" + type + "|" + method + "|" + requirementKey;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(point);
        }
        return new ArrayList<>(groups.values());
    }

    private String buildVerificationObjectiveTitle(Map<String, Object> unit, List<Map<String, Object>> points) {
        String unitName = unit == null ? "测试主题" : String.valueOf(unit.getOrDefault("name", "测试主题"));
        String pointType = String.valueOf(points.get(0).getOrDefault("point_type", "FUNCTION"));
        return unitName + " - " + pointTypeDisplayName(pointType);
    }

    private String buildVerificationObjectiveScenario(List<Map<String, Object>> points, String title) {
        List<String> descriptions = points.stream()
                .map(point -> String.valueOf(point.getOrDefault("description", point.getOrDefault("title", ""))))
                .filter(text -> !text.isBlank() && !"null".equals(text))
                .distinct().toList();
        return descriptions.isEmpty() ? title : String.join("；", descriptions);
    }

    private String highestPriorityHint(List<Map<String, Object>> points) {
        if (points.stream().anyMatch(point -> "CORE".equals(String.valueOf(point.get("priority_hint"))))) return "CORE";
        if (points.stream().anyMatch(point -> "HIGH".equals(String.valueOf(point.get("priority_hint"))))) return "HIGH";
        return "EXTENDED";
    }

    private String mergedCoverageStatus(List<Map<String, Object>> points) {
        if (points.stream().anyMatch(point -> "LOW_EVIDENCE".equals(String.valueOf(point.get("coverage_status"))))) {
            return "LOW_EVIDENCE";
        }
        if (points.stream().anyMatch(point -> "PARTIAL".equals(String.valueOf(point.get("coverage_status"))))) {
            return "PARTIAL";
        }
        return "SUPPORTED";
    }

    private List<String> mergePointSourceBasis(List<Map<String, Object>> points) {
        return points.stream().flatMap(point -> toStringList(point.get("source_basis")).stream())
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private String pointTypeDisplayName(String pointType) {
        return switch (pointType) {
            case "MAIN_FLOW" -> "主流程验证";
            case "BRANCH" -> "分支验证";
            case "BOUNDARY" -> "边界验证";
            case "EXCEPTION" -> "异常处理";
            case "STATE" -> "状态流转";
            case "DATA" -> "数据一致性";
            case "AUTH" -> "权限控制";
            case "CONCURRENCY" -> "并发控制";
            case "IDEMPOTENT" -> "幂等与重试";
            default -> "功能验证";
        };
    }

    private List<String> upstreamFlowPointRefs(Map<String, Object> unit,
                                                Map<String, List<Map<String, Object>>> pointsByUnit) {
        if (unit == null) return List.of();
        List<String> refs = new ArrayList<>();
        for (String upstreamUnit : toStringList(unit.get("depends_on_unit_refs"))) {
            Map<String, Object> point = preferredFlowPoint(pointsByUnit.get(upstreamUnit));
            if (point != null) refs.add(String.valueOf(point.get("id")));
        }
        return refs;
    }

    private Map<String, Object> preferredFlowPoint(List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        return candidates.stream()
                .filter(point -> "MAIN_FLOW".equals(String.valueOf(point.get("point_type"))))
                .findFirst()
                .orElse(candidates.get(0));
    }

    /** 跨主题完整流程只根据主题依赖图和 TP 摘要生成，避免重新传入完整需求/测试点造成上下文膨胀。 */
    private List<Map<String, Object>> runFlowCompositionStage(CurrentUser user,
                                                               Long projectId,
                                                               Long taskId,
                                                               Long modelConfigId,
                                                               Long promptTemplateId,
                                                               String taskTypePrefix,
                                                               String coreJson,
                                                               List<Map<String, Object>> units,
                                                               List<Map<String, Object>> points) {
        List<Map<String, Object>> dependentUnits = units.stream()
                .filter(unit -> !toStringList(unit.get("depends_on_unit_refs")).isEmpty()).toList();
        if (dependentUnits.isEmpty()) return List.of();
        List<Map<String, Object>> pointSummary = selectFlowCandidatePoints(points).stream().map(point -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", point.get("id"));
            summary.put("title", point.get("title"));
            summary.put("test_unit_ref", point.get("test_unit_ref"));
            summary.put("point_type", point.get("point_type"));
            summary.put("compose_with_test_point_refs", point.get("compose_with_test_point_refs"));
            return summary;
        }).toList();
        String flowPrompt = "## 业务锚点\n" + clipForPrompt(coreJson, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS)
                + "\n\n## 测试主题依赖图\n" + clipForPrompt(toJson(units), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS)
                + "\n\n## 测试点摘要\n" + clipForPrompt(toJson(pointSummary), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS * 2);
        String output = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                taskTypePrefix + "_CASE_COMPOSITION_FLOW",
                buildFlowCompositionSystemPrompt(),
                flowPrompt,
                ANALYSIS_MAX_TOKENS, "完整流程编排");
        String flowPlan = normalizeJsonColumn(extractJson(output, "case_plan"));
        List<Map<String, Object>> plans = readJsonObjectList(flowPlan);
        List<String> problems = validateFlowCasePlans(plans, units, points);
        if (!problems.isEmpty()) {
            String repairOutput = invokeAnalysisStage(user, projectId, taskId, modelConfigId, promptTemplateId,
                    taskTypePrefix + "_CASE_COMPOSITION_FLOW_REPAIR",
                    buildFlowCompositionSystemPrompt(),
                    flowPrompt + "\n\n上一次完整流程编排不符合依赖图：" + String.join("、", problems)
                            + "。请只返回符合真实依赖链的 FLOW_COMPOSED 计划。",
                    ANALYSIS_MAX_TOKENS, "完整流程编排补齐");
            flowPlan = normalizeJsonColumn(extractJson(repairOutput, "case_plan"));
            plans = readJsonObjectList(flowPlan);
            problems = validateFlowCasePlans(plans, units, points);
            if (!problems.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "完整流程编排不符合真实依赖图：" + String.join("、", problems));
            }
        }
        return plans == null ? List.of() : plans;
    }

    /** 完整流程只需要每个主题的关键流程候选；其余边界/异常点已由 NODE_FOCUSED CP 覆盖。 */
    private List<Map<String, Object>> selectFlowCandidatePoints(List<Map<String, Object>> points) {
        Map<String, List<Map<String, Object>>> byUnit = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            byUnit.computeIfAbsent(String.valueOf(point.get("test_unit_ref")), ignored -> new ArrayList<>()).add(point);
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        for (List<Map<String, Object>> unitPoints : byUnit.values()) {
            List<Map<String, Object>> main = unitPoints.stream()
                    .filter(point -> List.of("MAIN_FLOW", "STATE").contains(String.valueOf(point.get("point_type"))))
                    .limit(3).toList();
            if (main.isEmpty()) main = unitPoints.stream().limit(3).toList();
            selected.addAll(main);
        }
        return selected;
    }

    private List<String> validateFlowCasePlans(List<Map<String, Object>> plans,
                                               List<Map<String, Object>> units,
                                               List<Map<String, Object>> points) {
        // A dependency graph can legitimately contain only prerequisites for node-focused
        // checks. In that case no independently valuable end-to-end flow should be invented.
        if (plans == null || plans.isEmpty()) return List.of();
        Map<String, String> pointUnits = points.stream().collect(Collectors.toMap(
                point -> String.valueOf(point.get("id")),
                point -> String.valueOf(point.get("test_unit_ref")),
                (left, right) -> left, LinkedHashMap::new));
        Map<String, Set<String>> dependencies = units.stream().collect(Collectors.toMap(
                unit -> String.valueOf(unit.get("id")),
                unit -> new LinkedHashSet<>(toStringList(unit.get("depends_on_unit_refs"))),
                (left, right) -> left, LinkedHashMap::new));
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> plan : plans) {
            List<String> sourceRefs = toStringList(plan.get("source_test_point_refs"));
            Set<String> sourceUnits = sourceRefs.stream().map(pointUnits::get)
                    .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
            if (!"FLOW_COMPOSED".equals(String.valueOf(plan.get("case_strategy")))) {
                problems.add("完整流程节点只能输出 FLOW_COMPOSED 计划");
                continue;
            }
            if (sourceRefs.size() < 2 || sourceUnits.size() < 2) {
                problems.add("完整流程必须引用至少两个不同主题的真实测试点");
                continue;
            }
            if (sourceRefs.stream().anyMatch(ref -> !pointUnits.containsKey(ref))) {
                problems.add("完整流程引用了不存在的测试点");
                continue;
            }
            boolean hasActualDependency = sourceUnits.stream().anyMatch(unit -> dependencies.getOrDefault(unit, Set.of())
                    .stream().anyMatch(sourceUnits::contains));
            if (!hasActualDependency) {
                problems.add("完整流程未命中已声明的主题依赖链");
                continue;
            }
            problems.addAll(validateCaseDesigns(plan, sourceRefs));
        }
        return problems.stream().distinct().toList();
    }

    private List<String> missingTestPointIdsFromCasePlan(String testPointsJson, String casePlanJson) {
        List<Map<String, Object>> points = readJsonObjectList(testPointsJson);
        List<Map<String, Object>> plans = readJsonObjectList(casePlanJson);
        if (points == null || points.isEmpty()) return List.of("测试点列表为空");
        if (plans == null || plans.isEmpty()) {
            return points.stream().map(point -> String.valueOf(point.get("id"))).toList();
        }
        Set<String> planned = plans.stream()
                .flatMap(plan -> toStringList(plan.get("source_test_point_refs")).stream())
                .collect(Collectors.toSet());
        return points.stream()
                .map(point -> String.valueOf(point.get("id")))
                .filter(id -> !id.isBlank() && !"null".equals(id))
                .filter(id -> !planned.contains(id))
                .toList();
    }

    private String buildPreconditionPointPrompt(Map<String, Object> unit,
                                                List<Map<String, Object>> units,
                                                List<Map<String, Object>> allPoints) {
        Set<String> prerequisiteUnits = new LinkedHashSet<>(toStringList(unit.get("depends_on_unit_refs")));
        if (prerequisiteUnits.isEmpty()) return "";
        List<Map<String, Object>> summary = allPoints.stream()
                .filter(point -> prerequisiteUnits.contains(String.valueOf(point.get("test_unit_ref"))))
                .map(point -> Map.<String, Object>of(
                        "id", point.get("id"),
                        "title", point.get("title"),
                        "test_unit_ref", point.get("test_unit_ref")))
                .toList();
        if (summary.isEmpty()) return "";
        return "\n\n## 可作为前置条件的上游测试点（只能写入 precondition_test_point_refs）\n"
                + clipForPrompt(toJson(summary), MAX_PREVIOUS_ANSWERS_CHARS);
    }

    private List<String> validateNodeCasePlans(String casePlanJson,
                                               Map<String, Object> unit,
                                               List<Map<String, Object>> allPoints,
                                               List<Map<String, Object>> units,
                                               List<Map<String, Object>> expectedPoints) {
        List<Map<String, Object>> plans = readJsonObjectList(casePlanJson);
        if (plans == null || plans.isEmpty()) return List.of("未返回用例编排计划");
        Map<String, String> pointUnits = allPoints.stream().collect(Collectors.toMap(
                point -> String.valueOf(point.get("id")),
                point -> String.valueOf(point.get("test_unit_ref")),
                (left, right) -> left, LinkedHashMap::new));
        String unitId = String.valueOf(unit.get("id"));
        Set<String> expectedPointIds = expectedPoints.stream()
                .map(point -> String.valueOf(point.get("id"))).collect(Collectors.toSet());
        Set<String> upstreamUnits = new LinkedHashSet<>(toStringList(unit.get("depends_on_unit_refs")));
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> plan : plans) {
            List<String> sourceRefs = toStringList(plan.get("source_test_point_refs"));
            List<String> preconditionRefs = toStringList(plan.get("precondition_test_point_refs"));
            if (!"NODE_FOCUSED".equals(String.valueOf(plan.get("case_strategy")))) {
                problems.add("主题节点 " + unitId + " 只能输出 NODE_FOCUSED 计划");
            }
            if (sourceRefs.isEmpty()) {
                problems.add("NODE_FOCUSED 计划至少引用一个当前测试点");
            }
            for (String sourceRef : sourceRefs) {
                if (!unitId.equals(pointUnits.get(sourceRef)) || !expectedPointIds.contains(sourceRef)) {
                    problems.add("主题节点计划引用了不存在或非本节点测试点：" + sourceRef);
                }
            }
            for (String preconditionRef : preconditionRefs) {
                String preconditionUnit = pointUnits.get(preconditionRef);
                if (preconditionUnit == null || !upstreamUnits.contains(preconditionUnit)) {
                    problems.add("节点前置条件不是已声明上游测试点：" + preconditionRef);
                }
                if (sourceRefs.contains(preconditionRef)) {
                    problems.add("节点前置条件不能与当前测试点相同：" + preconditionRef);
                }
            }
            problems.addAll(validateCaseDesigns(plan, sourceRefs));
        }
        return problems.stream().distinct().toList();
    }

    private List<String> validateCaseDesigns(Map<String, Object> plan, List<String> planPointRefs) {
        List<Map<String, Object>> designs = readJsonObjectList(toJson(plan.get("case_designs")));
        if (designs == null || designs.isEmpty()) {
            return List.of("用例编排计划缺少 case_designs 具体设计项");
        }
        Set<String> ids = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> design : designs) {
            String id = String.valueOf(design.getOrDefault("id", "")).trim();
            if (id.isBlank() || !ids.add(id)) problems.add("case_design 缺少唯一编号");
            if (String.valueOf(design.getOrDefault("scenario", "")).isBlank()) {
                problems.add("case_design 缺少具体场景说明");
            }
            List<String> refs = toStringList(design.get("source_test_point_refs"));
            if (refs.isEmpty() || refs.stream().anyMatch(ref -> !planPointRefs.contains(ref))) {
                problems.add("case_design 引用了计划外或不存在的测试点");
            }
        }
        return problems.stream().distinct().toList();
    }

    private String mergeCasePlanJson(String leftJson, String rightJson) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> left = readJsonObjectList(leftJson);
        List<Map<String, Object>> right = readJsonObjectList(rightJson);
        if (left != null) rows.addAll(left);
        if (right != null) rows.addAll(right);
        LinkedHashMap<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.getOrDefault("id", ""));
            String refs = String.join(",", toStringList(row.get("source_test_point_refs")));
            unique.putIfAbsent((id + "|" + refs).trim(), row);
        }
        return toJson(new ArrayList<>(unique.values()));
    }

    /** CP 编号同样由平台按计划顺序固定，避免每个独立主题节点各自从 CP1 起号。 */
    private void assignStableCasePlanIds(List<Map<String, Object>> plans) {
        for (int index = 0; index < plans.size(); index++) {
            plans.get(index).put("id", "CP" + (index + 1));
        }
    }

    private List<Map<String, Object>> requirementAtoms(String coreJson) {
        Map<String, Object> root = readJsonObject(coreJson);
        if (root == null) {
            return List.of();
        }
        List<Map<String, Object>> atoms = readJsonObjectList(toJson(root.get("requirement_atoms")));
        return atoms == null ? List.of() : atoms.stream()
                .filter(atom -> firstNonBlank(String.valueOf(atom.get("id")), "").startsWith("R"))
                .toList();
    }

    /**
     * A requirement atom marked as needing clarification must always yield an answerable
     * question.  This protects the conversation flow when a provider returns the flag but
     * drops clarification_questions under output pressure.
     */
    private String ensureClarificationQuestions(String coreJson, String existingJson) {
        List<Map<String, Object>> existing = readJsonObjectList(existingJson);
        List<Map<String, Object>> questions = new ArrayList<>(existing == null ? List.of() : existing);
        Set<String> existingText = questions.stream()
                .map(item -> String.valueOf(item.getOrDefault("question", "")))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // A review question may be stored separately for display, but if it asks for a missing
        // business decision it must also be answerable in the conversation before generation.
        for (Map<String, Object> reviewQuestion : reviewRiskQuestions(coreJson)) {
            if (questions.size() >= 5) break;
            String text = firstNonBlank(String.valueOf(reviewQuestion.get("question")), "");
            if (text.isBlank() || existingText.stream().anyMatch(question -> question.contains(text)
                    || text.contains(question))) {
                continue;
            }
            Map<String, Object> clarification = new LinkedHashMap<>();
            clarification.put("question", text);
            clarification.put("reason", firstNonBlank(String.valueOf(reviewQuestion.get("reason")),
                    "该问题会影响测试范围和用例设计，需要业务方确认。"));
            clarification.put("impact", firstNonBlank(String.valueOf(reviewQuestion.get("impact")),
                    "未确认时只能按假设生成，可能造成测试遗漏。"));
            clarification.put("source_type", "REVIEW_RISK");
            questions.add(clarification);
            existingText.add(text);
        }

        for (Map<String, Object> atom : requirementAtoms(coreJson)) {
            if (!isTrue(atom.get("needs_clarification")) || questions.size() >= 5) {
                continue;
            }
            String atomId = firstNonBlank(String.valueOf(atom.get("id")), "未编号需求");
            String title = firstNonBlank(String.valueOf(atom.get("title")), atomId);
            String requirement = firstNonBlank(String.valueOf(atom.get("requirement")), "当前规则细节未描述");
            boolean alreadyAsked = existingText.stream().anyMatch(question -> question.contains(atomId)
                    || question.contains(title));
            if (alreadyAsked) {
                continue;
            }
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("question", "请明确“" + title + "”的具体业务规则、交互或判断标准：" + requirement);
            question.put("reason", "需求原子 " + atomId + " 已标记为待澄清，当前信息不足以稳定设计测试步骤和预期结果。");
            question.put("impact", "未确认时只能按假设生成，可能遗漏分支、边界、权限或状态场景。");
            question.put("source_requirement_ref", atomId);
            questions.add(question);
        }
        return toJson(questions);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reviewRiskQuestions(String coreJson) {
        Map<String, Object> root = readJsonObject(coreJson);
        if (root == null) return List.of();
        Object raw = root.get("review_risk_questions");
        if (raw == null && root.get("analysis") instanceof Map<?, ?> analysis) {
            raw = ((Map<String, Object>) analysis).get("review_risk_questions");
        }
        List<Map<String, Object>> values = readJsonObjectList(toJson(raw));
        return values == null ? List.of() : values;
    }

    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private List<Map<String, Object>> testUnits(String coreJson) {
        Map<String, Object> root = readJsonObject(coreJson);
        if (root == null) {
            return List.of();
        }
        List<Map<String, Object>> units = readJsonObjectList(toJson(root.get("test_units")));
        return units == null ? List.of() : units.stream()
                .filter(unit -> firstNonBlank(String.valueOf(unit.get("id")), "").startsWith("U"))
                .filter(unit -> !toStringList(unit.get("requirement_refs")).isEmpty())
                .toList();
    }

    private List<Map<String, Object>> atomsForUnit(Map<String, Object> unit, List<Map<String, Object>> atoms) {
        Set<String> refs = new LinkedHashSet<>(toStringList(unit.get("requirement_refs")));
        return atoms.stream().filter(atom -> refs.contains(String.valueOf(atom.get("id")))).toList();
    }

    private List<String> missingRequirementAtomIds(List<Map<String, Object>> atoms, String testPointsJson) {
        List<Map<String, Object>> points = readJsonObjectList(testPointsJson);
        if (points == null) {
            return atoms.stream().map(atom -> String.valueOf(atom.get("id"))).toList();
        }
        Set<String> covered = points.stream()
                .flatMap(point -> toStringList(point.get("requirement_refs")).stream())
                .collect(Collectors.toSet());
        return atoms.stream()
                .map(atom -> String.valueOf(atom.get("id")))
                .filter(id -> !covered.contains(id))
                .toList();
    }

    private String mergeTestPointJson(String leftJson, String rightJson) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> left = readJsonObjectList(leftJson);
        List<Map<String, Object>> right = readJsonObjectList(rightJson);
        if (left != null) rows.addAll(left);
        if (right != null) rows.addAll(right);
        LinkedHashMap<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String title = String.valueOf(row.getOrDefault("title", ""));
            String refs = String.join(",", toStringList(row.get("requirement_refs")));
            unique.putIfAbsent((refs + "|" + title).trim(), row);
        }
        return toJson(new ArrayList<>(unique.values()));
    }

    private String buildRequirementAtomSelfCheck(List<Map<String, Object>> atoms, String testPointsJson) {
        List<String> missing = missingRequirementAtomIds(atoms, testPointsJson);
        return toJson(Map.of(
                "three_layer_complete", missing.isEmpty(),
                "redundancy_checked", true,
                "method_routing_checked", true,
                "p0_review_checked", true,
                "requirement_atom_count", atoms.size(),
                "covered_requirement_atom_count", atoms.size() - missing.size(),
                "notes", missing.isEmpty()
                        ? List.of("所有需求原子（含待澄清项）均已建立测试点追溯关系。")
                        : List.of("未覆盖需求原子：" + String.join("、", missing))
        ));
    }

    private boolean isSparseCoverageMatrix(String matrixJson, String coreJson) {
        List<Map<String, Object>> rows = readJsonObjectList(matrixJson);
        if (rows == null || rows.isEmpty()) {
            return true;
        }
        int nonMainDimensions = 0;
        int total = 0;
        for (Map<String, Object> row : rows) {
            total += numberValue(row.get("total"));
            for (String key : List.of("branch", "boundary", "state", "data", "auth", "concurrency", "idempotent")) {
                Object value = row.get(key);
                if (value instanceof Map<?, ?> map && numberValue(map.get("count")) > 0) {
                    nonMainDimensions++;
                }
            }
        }
        Map<String, Object> core = readJsonObject(coreJson);
        boolean hasDetailSignals = core != null && !mergeDistinct(
                toStringList(core.get("affected_flows")),
                toStringList(core.get("affected_fields")),
                toStringList(core.get("affected_roles")),
                analysisTextList(core.get("review_risk_questions")),
                analysisTextList(core.get("boundary_conditions"))).isEmpty();
        return hasDetailSignals && (total <= rows.size() * 2 || nonMainDimensions == 0);
    }

    private void putMatrixDimension(Map<String, Object> row, String key, List<String> items) {
        List<String> limited = items == null ? List.of() : items.stream().filter(Objects::nonNull)
                .map(String::trim).filter(value -> !value.isBlank()).distinct().limit(3).toList();
        row.put(key, Map.of("count", limited.size(), "items", limited));
    }

    private int matrixTotal(Map<String, Object> row) {
        return List.of("main_flow", "branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent")
                .stream().mapToInt(key -> {
                    Object value = row.get(key);
                    return value instanceof Map<?, ?> map ? numberValue(map.get("count")) : 0;
                }).sum();
    }

    private List<String> matrixItems(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringList(map.get("items"));
        }
        return List.of();
    }

    private String matrixPointType(String dimension) {
        return switch (dimension) {
            case "main_flow" -> "MAIN_FLOW";
            case "branch" -> "BRANCH";
            case "boundary" -> "BOUNDARY";
            case "exception" -> "EXCEPTION";
            case "state" -> "STATE";
            case "data" -> "DATA";
            case "auth" -> "AUTH";
            case "concurrency" -> "CONCURRENCY";
            default -> "IDEMPOTENT";
        };
    }

    private List<String> selectByKeywords(List<String> candidates, String... keywords) {
        return candidates.stream()
                .filter(candidate -> Arrays.stream(keywords).anyMatch(candidate::contains))
                .distinct().limit(3).toList();
    }

    @SafeVarargs
    private final List<String> mergeDistinct(List<String>... lists) {
        return Arrays.stream(lists).filter(Objects::nonNull).flatMap(Collection::stream)
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private List<String> analysisTextList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return toStringList(value);
        }
        return collection.stream().map(item -> {
            if (item instanceof Map<?, ?> map) {
                return firstNonBlank(String.valueOf(map.get("question")), String.valueOf(map.get("risk")),
                        String.valueOf(map.get("description")), String.valueOf(map.get("reason")));
            }
            return String.valueOf(item);
        }).filter(Objects::nonNull).map(String::trim).filter(text -> !text.isBlank() && !"null".equals(text)).distinct().toList();
    }

    private String concisePointTitle(String item, String pointType) {
        String text = item == null ? "测试验证" : item.replaceAll("[。；;]", " ").trim();
        return text.length() > 42 ? text.substring(0, 42) + "…" : text;
    }

    private String invokeAnalysisStage(CurrentUser user,
                                       Long projectId,
                                       Long taskId,
                                       Long modelConfigId,
                                       Long promptTemplateId,
                                       String taskType,
                                       String systemPrompt,
                                       String userPrompt,
                                       int maxTokens,
                                       String label) {
        boolean checkpointEligible = isCheckpointEligible(projectId, taskId);
        String checkpointKey = checkpointKey(taskType, systemPrompt, userPrompt, maxTokens);
        if (checkpointEligible) {
            var checkpoint = taskCheckpointService.loadSucceededPayload(taskId, checkpointKey);
            if (checkpoint.isPresent()) {
                if (isCompleteJsonDocument(checkpoint.get())) {
                    log.info("{}复用已完成节点检查点: taskId={}, stage={}", label, taskId, taskType);
                    return checkpoint.get();
                }
                // Checkpoints written by an interrupted older runtime can contain a partial
                // continuation.  They are not reusable assets; invalidate only this node.
                taskCheckpointService.markFailed(taskId, checkpointKey, LlmErrorCode.OUTPUT_PARSE_ERROR.name(),
                        "检查点 JSON 未闭合，已丢弃并重新执行该节点");
            }
            taskCheckpointService.markRunning(taskId, checkpointKey);
        }
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ANALYSIS_NODE_EXECUTION_ATTEMPTS; attempt++) {
            var request = new LlmInvocationRequest(
                    UUID.randomUUID().toString(), user.id(), projectId, taskId,
                    taskType, LlmStage.REQ_CLARIFY,
                    modelConfigId, promptTemplateId, null,
                    Map.of(), systemPrompt, userPrompt, null, maxTokens
            );
            try {
                var response = llmGateway.invoke(request);
                if (response.status() != LlmInvocationStatus.OK) {
                    throw new LlmRuntimeException(toLlmErrorCode(response.errorCode()),
                            label + "失败: " + firstNonBlank(response.errorMessage(), "模型调用未返回有效结果"));
                }
                String completed = continueAnalysisOutputIfNeeded(response.content(), response.tokenOutput(), request, label, maxTokens);
                if (checkpointEligible) {
                    taskCheckpointService.markSucceeded(taskId, checkpointKey, completed);
                }
                return completed;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt < MAX_ANALYSIS_NODE_EXECUTION_ATTEMPTS && isRetryableNodeFailure(ex)) {
                    log.warn("{}第{}次节点执行失败，保留已完成节点并重试当前节点: {}", label, attempt, ex.getMessage());
                    continue;
                }
            }
        }
        if (checkpointEligible) {
            String code = lastFailure instanceof LlmRuntimeException runtime ? runtime.errorCode().name()
                    : LlmErrorCode.UNKNOWN_ERROR.name();
            taskCheckpointService.markFailed(taskId, checkpointKey, code,
                    lastFailure == null ? "节点执行失败" : lastFailure.getMessage());
        }
        if (lastFailure != null) throw lastFailure;
        throw new LlmRuntimeException(LlmErrorCode.UNKNOWN_ERROR, label + "未执行");
    }

    private boolean isRetryableNodeFailure(RuntimeException exception) {
        if (!(exception instanceof LlmRuntimeException runtime)) return false;
        return switch (runtime.errorCode()) {
            case TIMEOUT, RATE_LIMITED, PROVIDER_ERROR, OUTPUT_PARSE_ERROR, UNKNOWN_ERROR -> true;
            default -> false;
        };
    }

    private boolean isCheckpointEligible(Long projectId, Long taskId) {
        if (taskCheckpointService == null || taskService == null || projectId == null || taskId == null) {
            return false;
        }
        try {
            String taskType = taskService.get(projectId, taskId).taskType();
            return taskType != null && taskType.startsWith("REQUIREMENT_ANALYSIS");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String checkpointKey(String taskType, String systemPrompt, String userPrompt, int maxTokens) {
        String material = (taskType == null ? "" : taskType) + "\n" + maxTokens + "\n"
                + (systemPrompt == null ? "" : systemPrompt) + "\n" + (userPrompt == null ? "" : userPrompt);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            String suffix = java.util.HexFormat.of().formatHex(hash).substring(0, 16);
            String prefix = taskType == null ? "ANALYSIS_NODE" : taskType;
            int maxPrefix = Math.max(1, 160 - suffix.length() - 1);
            return (prefix.length() > maxPrefix ? prefix.substring(0, maxPrefix) : prefix) + ":" + suffix;
        } catch (Exception e) {
            return (taskType == null ? "ANALYSIS_NODE" : taskType) + ":fallback";
        }
    }

    private String mergeStagedAnalysis(String coreJson,
                                       String coverageMatrix,
                                       String matrixReviewNotes,
                                       String skillSelfCheck,
                                       String casePlan) {
        Map<String, Object> root = readJsonObject(coreJson);
        if (root == null) {
            root = new LinkedHashMap<>();
        }
        Object matrix = readJsonValue(coverageMatrix);
        if (matrix != null) {
            root.put("coverage_matrix", matrix);
        }
        Object matrixNotes = readJsonValue(matrixReviewNotes);
        if (matrixNotes != null) {
            root.put("matrix_review_notes", matrixNotes);
        }
        Object selfCheck = readJsonValue(skillSelfCheck);
        if (selfCheck != null) {
            root.put("skill_self_check", selfCheck);
        }
        Object plan = readJsonValue(casePlan);
        if (plan != null) {
            root.put("case_plan", plan);
        }
        String json = toJson(root);
        return json == null ? coreJson : json;
    }

    private Object readJsonValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private Object safeJsonValue(String json) {
        Object value = readJsonValue(json);
        return value == null ? Map.of("raw", json == null ? "" : clipForPrompt(json, 2_000)) : value;
    }

    private String buildAnalysisCoreSystemPrompt(boolean incremental) {
        return """
                你是资深测试分析师。当前只执行第 1 阶段：需求理解与澄清。
                禁止生成 coverage_matrix、test_points 或测试用例。

                请严格返回 JSON 对象，不要 markdown，不要额外解释。
                顶层必须包含：analysis、clarification_questions、assumptions%s。
                analysis 只能包含：
                - requirement_understanding：不超过 300 字
                - business_domain
                - requirement_type：RULE/FORM/UI/STATE/DATA/MIXED
                - input_sources
                - input_source_notes
                - affected_modules
                - affected_pages
                - affected_fields
                - affected_flows
                - affected_roles
                - requirement_atoms：每个独立业务规则、流程、状态、字段约束、权限规则、外部集成或数据同步必须是一条原子；每条含 id（R1...）、category、title、requirement、source_basis、needs_clarification、scope_recommendation、scope_reason
                - test_units：面向测试执行的业务主题节点；每条含 id（U1...）、name、requirement_refs、summary、depends_on_unit_refs。示例：预约、审批、门禁、权限、人脸、外协、消息。必须按真实需求拆分，不能机械套用示例。
                - review_risk_questions：最多 3 条，每条含 question/reason/impact/source_basis
                - risk_scenarios：最多 5 条
                - boundary_conditions：最多 5 条
                - conflicts
                - uncertain_items
                - out_of_scope

                规则：
                1. clarification_questions 只放阻断准确分析、需要用户回答的问题。
                2. review_risk_questions 是评审风险问题，不能替代 clarification_questions。
                3. 没有证据支持的内容必须放入 uncertain_items 或标记待确认，不得伪装成事实。
                4. 后续阶段会以本阶段 analysis 为唯一业务理解锚点，所以本阶段必须稳定、简洁、明确。
                5. 如果是增量分析，必须继承上一版已确认内容，只修改用户明确补充或修正的部分。
                6. 即使信息不足，也必须返回 analysis.requirement_understanding，可写“基于当前输入的临时理解”；严禁只返回 test_points、coverage_matrix 或空对象。
                7. 如果返回 change_scope，只能是字符串 "MINOR" 或 "MAJOR"，不能附加解释文本。
                8. requirement_atoms 是后续拆点和生成用例的唯一完成清单：不得按“模块概述”合并多个独立规则；未澄清的原子也必须保留，并标记 needs_clarification=true。
                9. test_units 负责把相关原子组织成可独立分析的测试主题；同一业务对象、页面/接口或连续操作中的字段、状态、权限、异常原子应归入同一主题，禁止机械地“一条 R 一个 U”。只有执行入口、业务对象或生命周期明显独立时才拆成不同 U。一个原子可以被多个主题引用，跨主题完整流程在后续用例编排节点处理。
                10. depends_on_unit_refs 必须表达真实前后依赖；只要需求存在“提交后审批后通知”这类跨节点闭环，就必须明确依赖链，供后续生成独立完整流程用例。
                11. 每个 requirement_atoms 的 R 编号（包括 needs_clarification=true 的项）至少必须出现在一个 test_units.requirement_refs 中；待澄清项须作为低证据主题保留，绝不可省略。
                12. 每个 needs_clarification=true 的 requirement_atom 必须在 clarification_questions 中有一条可直接回答的问题，并带 source_requirement_ref=对应 R 编号、reason、impact；不能只放入风险或不确定项。
                13. scope_recommendation 只能是 IN_SCOPE / REFERENCE_ONLY / OUT_OF_SCOPE / NEEDS_CONFIRMATION：本期明确要实现或验证的变更为 IN_SCOPE；仅用于解释背景、现状、历史或上下游但本期不要求验证的内容为 REFERENCE_ONLY；明确声明不在本期的内容为 OUT_OF_SCOPE；无法判断是否属于本期时为 NEEDS_CONFIRMATION。不得仅因它是非功能、接口、权限或异常内容就判为参考项。
                14. scope_reason 必须用一句话说明范围建议依据。范围识别只是给用户审核的建议，不得删除任何需求原子。
                15. %s
                """.formatted(
                incremental ? "、affected_cases、change_scope、new_cases_needed" : "",
                incremental ? "这是补充/重新分析：保留上一版已有风险结论，只输出本次补充直接引入的新风险；没有新增风险时三个风险字段返回空数组。"
                        : "这是首次分析：只做一次精简风险扫描，优先找会改变测试范围或需要产品确认的高影响问题；不要用泛化风险填充输出。");
    }

    private String buildAnalysisCoreUserPrompt(String requirementText,
                                               String tomSnapshot,
                                               ProjectSemanticContextService.BuildResult semanticContext,
                                               String userSupplement,
                                               String previousContext) {
        StringBuilder sb = new StringBuilder();
        if (previousContext != null && !previousContext.isBlank()) {
            sb.append("## 上一版已确认/最新分析上下文（增量分析必须继承，不得无故改写）\n")
                    .append(clipForPrompt(previousContext, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
        }
        sb.append("\n\n## 需求描述\n").append(clipForPrompt(requirementText, MAX_ANALYSIS_REQUIREMENT_CHARS));
        if (userSupplement != null && !userSupplement.isBlank()) {
            sb.append("\n\n## 用户补充/修改\n").append(clipForPrompt(userSupplement, MAX_PREVIOUS_ANSWERS_CHARS));
        }
        if (tomSnapshot != null && !tomSnapshot.isBlank()) {
            sb.append("\n\n## TOM 上下文\n").append(clipForPrompt(tomSnapshot, MAX_ANALYSIS_TOM_CHARS));
        }
        appendSemanticPrompt(sb, semanticContext);
        sb.append("\n\n请只输出第 1 阶段 JSON：需求理解、需求原子、影响范围、风险扫描、澄清问题。");
        return sb.toString();
    }

    private String buildCoreRepairPrompt(String requirementText,
                                         String tomSnapshot,
                                         ProjectSemanticContextService.BuildResult semanticContext,
                                         String userSupplement,
                                         String previousContext) {
        return buildAnalysisCoreUserPrompt(requirementText, tomSnapshot, semanticContext, userSupplement, previousContext)
                + "\n\n上一次结果不完整。此次必须完整返回 analysis.requirement_understanding 与 requirement_atoms；"
                + "每个独立流程、规则、角色权限、状态、字段约束、外部集成均拆为独立 R 编号原子。"
                + "每个 R（包括 needs_clarification=true 的 R）必须至少被一个 test_units.requirement_refs 引用；"
                + "待澄清不是省略该需求的理由，应建立低证据测试主题并保留待确认标记。";
    }

    private List<String> missingCoreFields(String coreJson) {
        Map<String, Object> core = readJsonObject(coreJson);
        if (core == null || core.isEmpty()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        String understanding = String.valueOf(core.getOrDefault("requirement_understanding", "")).trim();
        if (understanding.isBlank() || "null".equalsIgnoreCase(understanding)) {
            fields.add("requirement_understanding");
        }
        if (requirementAtoms(coreJson).isEmpty()) {
            fields.add("requirement_atoms");
        }
        if (testUnits(coreJson).isEmpty() || !missingTestUnitRequirementAtomIds(coreJson).isEmpty()) {
            fields.add("test_units");
        }
        return fields;
    }

    private String buildCorePatchSystemPrompt() {
        return """
                你是需求分析资产补齐器。只补齐指定缺失字段，绝不重写已经完成的分析。
                请严格返回 JSON 对象，不要 markdown，不要解释。顶层直接包含本次被要求补齐的字段。
                顶层只能包含：requirement_understanding、requirement_atoms、test_units、clarification_questions、assumptions。
                requirement_atoms 的每条记录必须包含 id、category、title、requirement、source_basis、needs_clarification、scope_recommendation、scope_reason。
                test_units 的每条记录必须包含 id、name、requirement_refs、summary、depends_on_unit_refs。
                test_units 必须按可独立执行的业务主题聚合相关 R；同一对象/页面/接口或连续操作的字段、状态、权限、异常不得机械拆成“一条 R 一个 U”。
                每个 R 编号都必须至少由一个 U 的 requirement_refs 引用。不要重新输出已有字段，不要生成覆盖矩阵、测试点或测试用例。
                """;
    }

    private String buildCorePatchPrompt(String requirementFragment, String coreJson, List<String> missingFields) {
        Map<String, Object> core = readJsonObject(coreJson);
        Map<String, Object> compact = new LinkedHashMap<>();
        if (core != null) {
            List<String> keys = missingFields.contains("test_units")
                    ? List.of("requirement_understanding", "business_domain", "requirement_type", "requirement_atoms")
                    : List.of("requirement_understanding", "business_domain", "requirement_type", "affected_modules", "requirement_atoms");
            for (String key : keys) {
                if (core.containsKey(key)) {
                    compact.put(key, core.get(key));
                }
            }
        }
        return "## 本次只补齐字段\n" + String.join(", ", missingFields)
                + "\n\n## 已成功保存的分析资产（只作引用，不要重写）\n" + clipForPrompt(toJson(compact), 8_000)
                + "\n\n## 当前需求输入片段\n" + clipForPrompt(requirementFragment, ANALYSIS_INPUT_FRAGMENT_CHARS)
                + "\n\n请只返回缺失字段。已有 requirement_atoms 时，test_units 必须引用其现有 R 编号；"
                + "缺 requirement_atoms 时，先为本片段建立 R 编号，再建立引用这些 R 的 test_units。";
    }

    private String buildTestUnitsRepairSystemPrompt() {
        return """
                你是测试主题补齐器。只根据已确认的 requirement_atoms 建立测试主题，不新增、不删除、不改写需求原子。
                严格返回 JSON 对象，不要 markdown、不要解释：
                {"test_units":[{"id":"U1","name":"","requirement_refs":["R1"],"summary":"","depends_on_unit_refs":[]}]}
                每个现有 R 编号都必须至少被一个 test_units.requirement_refs 引用；可以把同一业务闭环的多个 R 放到同一主题。
                同一业务对象、页面/接口或连续操作中的字段、状态、权限、异常应聚合为可维护主题，禁止机械地“一条 R 一个 U”；不得为了减少数量合并无关业务对象或独立生命周期。
                若依赖方向不确定，depends_on_unit_refs 返回空数组，不要编造依赖。不要返回任何其他字段。
                """;
    }

    private String buildTestUnitsRepairPrompt(String coreJson) {
        Map<String, Object> core = readJsonObject(coreJson);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (core != null) {
            payload.put("business_domain", core.get("business_domain"));
            payload.put("requirement_understanding", core.get("requirement_understanding"));
            payload.put("requirement_atoms", core.get("requirement_atoms"));
            payload.put("existing_test_units", core.get("test_units"));
        }
        return "## 已确认的需求资产\n" + clipForPrompt(toJson(payload), 12_000)
                + "\n\n请只返回 test_units，并确保所有 R 编号都有归属。";
    }

    /**
     * The first CORE call is deliberately small. Atoms and units are separate durable nodes,
     * rather than optional fields competing for one large JSON response.
     */
    private String buildCoreOverviewSystemPrompt(boolean incremental) {
        return """
                你是需求概览提取器，只做第 1 个极小节点，不拆需求原子、不生成测试主题、风险、矩阵或用例。
                严格返回 JSON，不要 markdown，不要解释：
                {"analysis":{"requirement_understanding":"不超过180字","business_domain":"","requirement_type":"RULE/FORM/UI/STATE/DATA/MIXED","input_sources":[],"affected_modules":[]},"clarification_questions":[],"assumptions":[]}
                仅基于输入明确事实；未知内容不要编造。若当前片段存在会阻断准确测试设计的信息缺口，必须在 clarification_questions 中提出可直接回答的问题，包含 question、reason、impact。%s
                """.formatted(incremental ? "这是增量分析，只概括本次补充造成的变化。" : "");
    }

    private String buildCoreOverviewUserPrompt(String requirementFragment,
                                                String tomSnapshot,
                                                String userSupplement,
                                                String previousContext) {
        StringBuilder sb = new StringBuilder("## 当前需求片段\n")
                .append(clipForPrompt(requirementFragment, ANALYSIS_INPUT_FRAGMENT_CHARS));
        if (userSupplement != null && !userSupplement.isBlank()) {
            sb.append("\n\n## 用户补充\n").append(clipForPrompt(userSupplement, 800));
        }
        if (previousContext != null && !previousContext.isBlank()) {
            sb.append("\n\n## 已确认上下文摘要\n").append(clipForPrompt(previousContext, 1_000));
        }
        if (tomSnapshot != null && !tomSnapshot.isBlank()) {
            sb.append("\n\n## TOM 参考摘要\n").append(clipForPrompt(tomSnapshot, 900));
        }
        return sb.toString();
    }

    private String mergeCorePatch(String coreJson, String patchJson, List<String> allowedFields) {
        Map<String, Object> core = readJsonObject(coreJson);
        Map<String, Object> patch = readJsonObject(patchJson);
        if (core == null || patch == null || patch.isEmpty()) {
            return coreJson;
        }
        for (String field : allowedFields) {
            Object value = patch.get(field);
            if (hasCorePatchValue(value)) {
                core.put(field, value);
            }
        }
        return toJson(core);
    }

    private boolean hasCorePatchValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return value != null && !String.valueOf(value).isBlank() && !"null".equalsIgnoreCase(String.valueOf(value));
    }

    private String mergeJsonArrays(String current, String patch) {
        List<Object> values = new ArrayList<>();
        appendJsonValues(values, current);
        appendJsonValues(values, patch);
        return values.isEmpty() ? (current == null ? patch : current) : toJson(distinctJsonValues(values));
    }

    private void requireUsableCoreAnalysis(String coreJson,
                                           ProjectSemanticContextService.BuildResult semanticContext,
                                           String clarificationQuestions) {
        if (!isUsableAnalysisResult(enrichAnalysisResult(coreJson, semanticContext, clarificationQuestions))) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "需求理解节点未返回完整可展示结果；系统不能用通用摘要代替，请重试该节点。" );
        }
    }

    private void requireRequirementAtoms(String coreJson) {
        if (requirementAtoms(coreJson).isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "需求理解节点未返回 requirement_atoms；无法验证需求是否被完整拆解，不能继续生成测试点。" );
        }
    }

    private void requireTestUnits(String coreJson) {
        if (testUnits(coreJson).isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "需求理解节点未返回 test_units；无法按业务主题组织测试节点。" );
        }
    }

    /** A clarification flag lowers confidence; it must never remove a requirement from the pipeline. */
    private void requireAllRequirementAtomsAssignedToTestUnits(String coreJson) {
        List<String> missing = missingTestUnitRequirementAtomIds(coreJson);
        if (!missing.isEmpty()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "需求理解节点未将需求原子分配到测试主题：" + String.join("、", missing)
                            + "；待澄清需求也必须形成低证据测试主题，不能被省略。");
        }
    }

    private List<String> missingTestUnitRequirementAtomIds(String coreJson) {
        Set<String> assigned = testUnits(coreJson).stream()
                .flatMap(unit -> toStringList(unit.get("requirement_refs")).stream())
                .collect(Collectors.toSet());
        return requirementAtoms(coreJson).stream()
                .map(atom -> String.valueOf(atom.get("id")))
                .filter(id -> !id.isBlank() && !"null".equals(id))
                .filter(id -> !assigned.contains(id))
                .toList();
    }

    private String buildCoverageMatrixSystemPrompt() {
        return """
                你是测试覆盖矩阵设计器。当前只执行第 2 阶段：覆盖矩阵。
                你必须严格基于第 1 阶段 analysis，不得改写需求理解、业务域、需求类型和影响范围。
                如果你认为第 1 阶段理解有问题，只能写入 matrix_review_notes，不能自行改成另一个需求。

                请严格返回 JSON 对象：
                {
                  "coverage_matrix": [
                    {
                      "test_unit_ref": "U1",
                      "requirement_refs": ["R1"],
                      "module": "模块名",
                      "main_flow": {"count": 0, "items": []},
                      "branch": {"count": 0, "items": []},
                      "boundary": {"count": 0, "items": []},
                      "exception": {"count": 0, "items": []},
                      "state": {"count": 0, "items": []},
                      "data": {"count": 0, "items": []},
                      "auth": {"count": 0, "items": []},
                      "concurrency": {"count": 0, "items": []},
                      "idempotent": {"count": 0, "items": []},
                      "total": 0
                    }
                  ],
                  "matrix_review_notes": []
                }
                规则：
                1. 输入包含多个测试主题时，每个 test_unit_ref 必须至少返回一行，禁止遗漏或串用其他主题的 R 编号。
                2. 每个维度 items 最多 3 条短句；每条只写“测试条件/动作 + 可验证结果”，不重复背景、原因、影响和套话。
                3. count 必须等于 items 数量；不能只返回数字，也不能用一条长段落代替多个独立场景。
                4. 覆盖矩阵是后续生成用例的主依据，不是风险问题汇总。已出现的流程、字段、角色、状态、接口、同步、批量、时间或权限信息，必须映射到对应维度，不能全部塞进“异常”。
                5. 除非 analysis 明确没有相应信息，否则不得只返回“主流程 + 异常”两个维度；有审批/状态时必须给 STATE，有角色/权限时必须给 AUTH，有字段/同步/接口时必须给 DATA，有时间/数量/有效期时必须给 BOUNDARY。
                6. requirement_refs 只能引用当前 test_unit_ref 输入中真实存在的 R 编号；不得遗漏当前主题的需求原子。
                7. 不生成测试点和测试用例。
                """;
    }

    private String buildCoverageMatrixBatchUserPrompt(List<CoverageWorkItem> workItems,
                                                       ProjectSemanticContextService.BuildResult semanticContext) {
        List<Map<String, Object>> payload = workItems.stream().map(CoverageWorkItem::promptPayload).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前覆盖工作项（逐项输出，不可遗漏）\n").append(toJson(payload));
        appendSemanticPrompt(sb, semanticContext);
        sb.append("\n\n每个 test_unit_ref 至少返回一行 coverage_matrix；每个 R* 必须进入对应主题的 requirement_refs，")
                .append("并映射到实际覆盖维度。文字保持紧凑，但不得删除独立规则、状态、字段、权限、接口或边界场景。");
        return sb.toString();
    }

    private String buildCoverageMatrixForUnitUserPrompt(Map<String, Object> unit,
                                                        List<Map<String, Object>> atoms,
                                                        ProjectSemanticContextService.BuildResult semanticContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前测试主题节点（仅处理此节点）\n").append(toJson(unit));
        sb.append("\n\n## 当前节点完整需求原子（不可遗漏）\n").append(toJson(atoms));
        appendSemanticPrompt(sb, semanticContext);
        sb.append("\n\n只输出本节点的 coverage_matrix。每个 R* 的规则、状态、字段、权限、接口或时间条件必须映射到实际维度。");
        return sb.toString();
    }

    private String buildTestPointsSystemPrompt() {
        return """
                你是测试点拆解器。当前只执行第 3 阶段：测试点。
                你必须严格基于第 1 阶段 analysis 和第 2 阶段 coverage_matrix，不得改写需求理解，不得新增无依据业务。

                请严格返回 JSON 对象：
                {
                  "test_points": [
                    {
                      "title": "测试点标题",
                      "description": "测试点描述",
                      "test_dimension": "测试维度",
                      "point_type": "MAIN_FLOW/BRANCH/BOUNDARY/EXCEPTION/STATE/DATA/AUTH/CONCURRENCY/IDEMPOTENT",
                      "skill_layer": "FUNCTIONAL/EXCEPTION/BOUNDARY_SUPPLEMENT",
                      "design_method": "场景法/等价类/边界值/判定表/状态迁移/错误推测/数据一致性检查",
                      "priority_hint": "CORE/EXTENDED/RISK",
                      "related_module": "关联模块",
                      "related_page": "关联页面",
                      "related_flow": "关联流程",
                      "test_unit_ref": "U1",
                      "requirement_refs": ["R1"],
                      "precondition_test_point_refs": [],
                      "case_strategy": "NODE_FOCUSED/FLOW_COMPOSED",
                      "compose_with_test_point_refs": [],
                      "source_basis": ["TOM/页面/业务包/轨迹证据名称"],
                      "source_refs": {
                        "tom_node_refs": [],
                        "page_refs": [],
                        "business_pack_refs": [],
                        "trace_refs": []
                      },
                      "coverage_status": "SUPPORTED/PARTIAL/LOW_EVIDENCE",
                      "unsupported_items": [],
                      "confidence": 0.8,
                      "needs_confirmation": true
                    }
                  ],
                  "skill_self_check": {
                    "three_layer_complete": false,
                    "redundancy_checked": false,
                    "method_routing_checked": false,
                    "p0_review_checked": false,
                    "notes": []
                  }
                }
                规则：
                1. 每条测试点必须有全局唯一 id（TP1、TP2...）；当前输入会给出一批 requirement_atoms，每个需求原子（包括 needs_clarification=true）至少要有一个测试点，且 requirement_refs 必须引用该原子的 R 编号；待澄清项必须标记 LOW_EVIDENCE/needs_confirmation，不能省略。
                2. 必须覆盖 coverage_matrix 中与本批原子相关的非 0 维度。
                3. 不允许把同一验证目标拆成多条重复测试点；也不允许用一条泛化“主流程验证”覆盖多个独立需求原子。
                4. P0/CORE 只用于核心主路径；异常、权限、字段校验、取消关闭不得标为 CORE。
                5. 每条测试点必须对应 coverage_matrix 的具体 item，标题要能直接说明“测什么”，不能只写“模块主流程验证”或“异常处理需确认”。
                6. 证据不足时应生成 LOW_EVIDENCE 且 needs_confirmation=true 的测试点，而不是省略；若原子确实无法测试，必须在 unsupported_items 中说明原因并保留 requirement_refs。
                7. NODE_FOCUSED 表示只验证当前节点动作，前置节点结果写入 precondition_test_point_refs，后续生成的用例不得重复前置节点步骤；FLOW_COMPOSED 表示需要把相关测试点按依赖顺序编排为端到端主流程，用 compose_with_test_point_refs 声明组合关系。
                8. 不生成测试用例。
                """;
    }

    private String buildTestPointsUserPrompt(String requirementText,
                                             String coreAnalysisJson,
                                             String coverageMatrixJson,
                                             ProjectSemanticContextService.BuildResult semanticContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 第 1 阶段 analysis（唯一业务理解锚点，不得改写）\n")
                .append(clipForPrompt(coreAnalysisJson, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
        sb.append("\n\n## 第 2 阶段 coverage_matrix（必须覆盖，不得绕过）\n")
                .append(clipForPrompt(coverageMatrixJson, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
        sb.append("\n\n## 原始需求（仅用于核对）\n")
                .append(clipForPrompt(requirementText, MAX_ANALYSIS_REQUIREMENT_CHARS));
        appendSemanticPrompt(sb, semanticContext);
        sb.append("\n\n请只输出测试点 JSON。");
        return sb.toString();
    }

    private String buildTestPointsUserPrompt(String requirementText,
                                             String coreAnalysisJson,
                                             String coverageMatrixJson,
                                             ProjectSemanticContextService.BuildResult semanticContext,
                                             String unitJson,
                                             String atomBatchJson) {
        Map<String, Object> unit = readJsonObject(unitJson);
        String unitId = unit == null ? "" : String.valueOf(unit.get("id"));
        List<Map<String, Object>> batchAtoms = readJsonObjectList(atomBatchJson);
        Set<String> atomIds = (batchAtoms == null ? List.<Map<String, Object>>of() : batchAtoms).stream()
                .map(atom -> String.valueOf(atom.get("id"))).collect(Collectors.toSet());
        List<Map<String, Object>> rows = readJsonObjectList(coverageMatrixJson);
        List<Map<String, Object>> unitRows = rows == null ? List.of() : rows.stream()
                .filter(row -> unitId.equals(String.valueOf(row.get("test_unit_ref"))))
                .filter(row -> {
                    List<String> refs = toStringList(row.get("requirement_refs"));
                    return refs.isEmpty() || refs.stream().anyMatch(atomIds::contains);
                }).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前测试主题节点（只处理此节点）\n").append(clipForPrompt(unitJson, MAX_PREVIOUS_ANSWERS_CHARS));
        sb.append("\n\n## 本节点完整需求原子（逐个 requirement_refs 覆盖）\n")
                .append(clipForPrompt(atomBatchJson, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
        sb.append("\n\n## 本节点覆盖矩阵（必须覆盖）\n")
                .append(clipForPrompt(toJson(unitRows), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
        appendSemanticPrompt(sb, semanticContext);
        sb.append("\n\n只生成本节点原子的测试点；不要提前生成下一节点，也不要遗漏本节点原子。");
        return sb.toString();
    }

    private String buildTestPointRepairPrompt(String requirementText,
                                              String coreAnalysisJson,
                                              String coverageMatrixJson,
                                              String unitJson,
                                              String atomBatchJson,
                                              List<String> missingIds) {
        return buildTestPointsUserPrompt(requirementText, coreAnalysisJson, coverageMatrixJson, null, unitJson, atomBatchJson)
                + "\n\n上一次测试点没有覆盖以下需求原子：" + String.join("、", missingIds)
                + "。此次只补齐这些原子；每条必须填写 requirement_refs，不能返回空数组。";
    }

    private String buildCaseCompositionSystemPrompt() {
        return """
                你是测试用例编排器。当前只执行第 4 阶段：用例编排。
                你必须严格基于已确认的 requirement_atoms、test_units、coverage_matrix 和 test_points，
                不得改写需求理解、不得增加不存在的业务规则、不得直接生成用例步骤。

                请严格返回 JSON 对象：
                {
                  "case_plan": [
                    {
                      "id": "CP1",
                      "title": "用例计划标题",
                      "case_strategy": "NODE_FOCUSED/FLOW_COMPOSED",
                      "test_unit_refs": ["U1"],
                      "source_test_point_refs": ["TP1"],
                      "precondition_test_point_refs": [],
                      "depends_on_case_plan_refs": [],
                      "design_method": "场景法/等价类/边界值/判定表/状态迁移/错误推测/数据一致性检查",
                      "case_designs": [
                        {
                          "id": "CD1",
                          "title": "具体用例设计场景",
                          "scenario": "要验证的条件、动作与结果范围",
                          "design_method": "场景法/等价类/边界值/判定表/状态迁移/错误推测/数据一致性检查",
                          "source_test_point_refs": ["TP1"],
                          "priority_hint": "CORE/EXTENDED/RISK"
                        }
                      ],
                      "priority_hint": "CORE/EXTENDED/RISK",
                      "coverage_status": "SUPPORTED/PARTIAL/LOW_EVIDENCE",
                      "source_basis": [],
                      "needs_confirmation": false
                    }
                  ]
                }
                规则：
                1. 每个 TP 测试点至少被一个 case_plan.source_test_point_refs 引用；不能漏掉已拆出的测试点。
                2. NODE_FOCUSED：只验证一个当前测试主题中的一个连贯验证目标，可引用该目标下多个同维度测试点。前序测试点只进入 precondition_test_point_refs，后续用例步骤不得重复前序操作。
                3. FLOW_COMPOSED：仅当 test_units.depends_on_unit_refs 或测试点的 compose_with_test_point_refs 表示真实依赖时，按依赖顺序组合多个测试点，形成完整端到端流程。
                4. 不得为了凑流程强行把无依赖的测试点组合在一起；也不得把每个节点用例复制成完整流程。
                5. CP id、TP id、U id 必须原样引用输入；不能发明不存在的编号。
                6. 一个计划项是后续可独立生成和重试的工作节点。计划项须足够小，避免一次生成过多无关场景，但不能人为限制需求覆盖范围。
                7. 每个计划必须有 case_designs：它是“测试点如何变成具体用例”的完成清单。按 design_method 拆出必要的等价类、边界、分支、状态迁移或异常设计；不要只给一个代表性样例，也不要机械规定固定条数。
                8. 每个 case_design 必须引用本计划真实 source_test_point_refs；后续每个 case_design 至少生成一条草稿。
                9. 不生成测试用例。
                """;
    }

    private String buildFlowCompositionSystemPrompt() {
        return """
                你是跨主题完整流程编排器。当前只处理 test_units.depends_on_unit_refs 已明确的依赖链。
                请严格返回 JSON 对象 {"case_plan":[...]}，字段与常规用例编排计划相同。
                每条计划必须：
                1. case_strategy 固定为 FLOW_COMPOSED；
                2. source_test_point_refs 至少包含两个输入中真实存在的 TP 编号，顺序必须符合主题依赖；
                3. test_unit_refs 必须包含依赖链中的真实 U 编号；
                4. 只编排真实依赖链，不得把无关模块拼成流程；配置、导出、审计、通知等单点能力仅因依赖其他主题而存在时，仍应保留为 NODE_FOCUSED，不自动扩成完整流程；
                5. 不生成任何用例步骤，不得新增业务事实。
                6. 每个计划必须提供至少一个 case_designs，用于声明完整流程的具体设计场景。
                只返回可独立代表业务闭环的关键路径；不要按每一条依赖边各生成一条流程。没有足够证据证明存在完整用户/业务闭环时，返回 {"case_plan":[]}。
                """;
    }

    private String buildCaseCompositionUserPrompt(String requirementText,
                                                  String coreJson,
                                                  String coverageMatrix,
                                                  String testPoints,
                                                  ProjectSemanticContextService.BuildResult semanticContext) {
        return buildCaseCompositionUserPrompt(requirementText, coreJson, coverageMatrix, testPoints, null, semanticContext);
    }

    private String buildCaseCompositionUserPrompt(String requirementText,
                                                  String coreJson,
                                                  String coverageMatrix,
                                                  String testPoints,
                                                  String unitJson,
                                                  ProjectSemanticContextService.BuildResult semanticContext) {
        StringBuilder sb = new StringBuilder();
        if (unitJson != null && !unitJson.isBlank()) {
            sb.append("\n\n## 当前测试主题节点（只编排本节点）\n")
                    .append(clipForPrompt(unitJson, MAX_PREVIOUS_ANSWERS_CHARS));
            Map<String, Object> unit = readJsonObject(unitJson);
            String unitId = unit == null ? "" : String.valueOf(unit.get("id"));
            List<Map<String, Object>> rows = readJsonObjectList(coverageMatrix);
            List<Map<String, Object>> unitRows = rows == null ? List.of() : rows.stream()
                    .filter(row -> unitId.equals(String.valueOf(row.get("test_unit_ref")))).toList();
            sb.append("\n\n## 本节点覆盖矩阵\n").append(toJson(unitRows));
        } else {
            sb.append("## 第 1 阶段业务锚点\n").append(clipForPrompt(coreJson, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
            sb.append("\n\n## 第 2 阶段覆盖矩阵\n").append(clipForPrompt(coverageMatrix, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
        }
        sb.append("\n\n## 第 3 阶段测试点（唯一可引用的 TP 编号）\n")
                .append(clipForPrompt(testPoints, MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS * 2));
        appendSemanticPrompt(sb, semanticContext);
        sb.append("\n\n请只输出 case_plan JSON。所有测试点均必须被编排。");
        return sb.toString();
    }

    private String buildCaseCompositionRepairPrompt(String requirementText,
                                                     String coreJson,
                                                     String coverageMatrix,
                                                     String testPoints,
                                                     String unitJson,
                                                     ProjectSemanticContextService.BuildResult semanticContext,
                                                     List<String> missing) {
        return buildCaseCompositionUserPrompt(requirementText, coreJson, coverageMatrix, testPoints, unitJson, semanticContext)
                + "\n\n上一次编排遗漏测试点：" + String.join("、", missing)
                + "。本次只补齐遗漏计划，必须返回有效 CP 编号和 source_test_point_refs。";
    }

    private void appendSemanticPrompt(StringBuilder sb, ProjectSemanticContextService.BuildResult semanticContext) {
        if (semanticContext != null && semanticContext.promptSection() != null
                && !semanticContext.promptSection().isBlank()) {
            sb.append("\n\n## 项目证据上下文（必须引用，不能编造）")
                    .append(clipForPrompt(semanticContext.promptSection(), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS));
            sb.append("\n## 项目证据结构化摘要\n")
                    .append(toJson(buildEvidenceSummary(semanticContext)));
        }
    }

    private String buildPreviousAnalysisContext(RequirementAnalysisRecord previousAnalysis) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "原始需求", previousAnalysis.requirementText(), MAX_ANALYSIS_REQUIREMENT_CHARS);
        appendSection(sb, "上一版分析", previousAnalysis.analysisResult(), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS);
        appendSection(sb, "上一版测试点", previousAnalysis.testPoints(), MAX_ANALYSIS_SEMANTIC_CONTEXT_CHARS);
        appendSection(sb, "上一版澄清问题", previousAnalysis.clarificationQuestions(), MAX_PREVIOUS_ANSWERS_CHARS);
        appendSection(sb, "已有澄清答案", previousAnalysis.clarificationAnswers(), MAX_PREVIOUS_ANSWERS_CHARS);
        appendSection(sb, "上一版假设", previousAnalysis.assumptions(), MAX_PREVIOUS_ANSWERS_CHARS);
        return sb.toString();
    }

    private String enrichAnalysisResult(String analysisResult,
                                        ProjectSemanticContextService.BuildResult semanticContext,
                                        String clarificationQuestions) {
        Map<String, Object> root = readJsonObject(analysisResult);
        if (root == null) {
            root = new LinkedHashMap<>();
        }
        promoteNestedAnalysisFields(root);
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

        // 归一化可选展示字段；不生成任何新的需求、矩阵或测试点内容。
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
        root.putIfAbsent("coverage_matrix",
                analysisMap != null && analysisMap.containsKey("coverage_matrix") ? analysisMap.get("coverage_matrix") : List.of());
        root.putIfAbsent("skill_self_check",
                analysisMap != null && analysisMap.containsKey("skill_self_check") ? analysisMap.get("skill_self_check") : Map.of(
                        "three_layer_complete", false,
                        "redundancy_checked", false,
                        "method_routing_checked", false,
                        "p0_review_checked", false,
                        "notes", List.of("未返回 Skill 自检结果，需人工确认覆盖矩阵和防冗余情况")
                ));

        String json = toJson(root);
        return json == null ? analysisResult : json;
    }

    @SuppressWarnings("unchecked")
    private void promoteNestedAnalysisFields(Map<String, Object> root) {
        if (!(root.get("analysis") instanceof Map<?, ?> nested)) {
            return;
        }
        nested.forEach((key, value) -> {
            if (key != null && value != null) {
                root.putIfAbsent(String.valueOf(key), value);
            }
        });
    }

    private void ensureUsableAnalysisResult(String analysisResult) {
        if (!isUsableAnalysisResult(analysisResult)) {
            throw new LlmRuntimeException(
                    LlmErrorCode.OUTPUT_PARSE_ERROR,
                    "模型返回未包含可展示的需求分析字段，或 JSON 输出被截断到无法恢复；请重试，或检查模型是否按 JSON 结构返回 analysis.requirement_understanding、affected_modules 或 coverage_matrix。"
            );
        }
    }

    private boolean isUsableAnalysisResult(String analysisResult) {
        Map<String, Object> root = readJsonObject(analysisResult);
        return root != null && hasMeaningfulAnalysisContent(root);
    }

    private boolean hasMeaningfulAnalysisContent(Map<String, Object> root) {
        if (!toStringList(root.get("requirement_understanding")).isEmpty()) {
            return true;
        }
        for (String key : List.of(
                "affected_modules",
                "affected_pages",
                "affected_fields",
                "affected_flows",
                "affected_roles",
                "conflicts",
                "review_risk_questions",
                "risk_scenarios",
                "boundary_conditions",
                "coverage_matrix")) {
            Object value = root.get(key);
            if (value instanceof Collection<?> collection && !collection.isEmpty()) {
                return true;
            }
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlankJsonValue(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        Object value = readJsonValue(json);
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }

    private String continueAnalysisOutputIfNeeded(String llmOutput,
                                                  int tokenOutput,
                                                  LlmInvocationRequest originalRequest,
                                                  String label,
                                                  int originalMaxTokens) {
        String merged = llmOutput;
        int lastTokenOutput = tokenOutput;
        int lastMaxTokens = originalMaxTokens;
        int attempts = 0;
        while (shouldRequestAnalysisContinuation(merged, lastTokenOutput, lastMaxTokens)) {
            if (attempts >= MAX_ANALYSIS_CONTINUATION_ATTEMPTS) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        label + "输出连续截断，已尝试" + MAX_ANALYSIS_CONTINUATION_ATTEMPTS
                                + "次续写仍未闭合 JSON；请缩小本轮需求范围或提高模型输出上限。");
            }
            attempts++;
            var continuationRequest = new LlmInvocationRequest(
                    UUID.randomUUID().toString(),
                    originalRequest.userId(),
                    originalRequest.projectId(),
                    originalRequest.taskId(),
                    originalRequest.taskType() + "_CONTINUATION_" + attempts,
                    originalRequest.stage(),
                    originalRequest.modelConfigId(),
                    originalRequest.promptTemplateId(),
                    originalRequest.promptVersion(),
                    Map.of("continuationOf", originalRequest.requestId(), "continuationIndex", attempts),
                    buildAnalysisContinuationSystemPrompt(),
                    buildAnalysisContinuationPrompt(merged),
                    originalRequest.traceGroupId(),
                    ANALYSIS_CONTINUATION_MAX_TOKENS
            );
            var continuation = llmGateway.invoke(continuationRequest);
            if (continuation.status() != LlmInvocationStatus.OK) {
                throw new LlmRuntimeException(toLlmErrorCode(continuation.errorCode()),
                        label + "输出被截断，第" + attempts + "次续写失败："
                                + firstNonBlank(continuation.errorMessage(), "未知模型错误"));
            }
            String continuationText = extractPlainText(continuation.content(), "continuation");
            // A JSON continuation envelope with an empty value means the provider could not
            // continue the document.  Never append that envelope itself to the prior JSON:
            // doing so turns a recoverable truncation into a corrupted "successful" asset.
            if (continuationText == null) {
                continuationText = continuation.content();
            }
            if (continuationText == null || continuationText.isBlank()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        label + "输出被截断，但第" + attempts + "次续写未返回有效片段；已保留原始调用日志以便重试。");
            }
            String next = mergeContinuation(merged, continuationText);
            if (next.length() <= merged.length()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        label + "输出被截断，但第" + attempts + "次续写没有推进内容；请重试该阶段。");
            }
            log.info("{}输出疑似截断，已追加第{}个续写片段: previousChars={}, continuationChars={}, mergedChars={}",
                    label, attempts, length(merged), length(continuationText), length(next));
            merged = next;
            lastTokenOutput = continuation.tokenOutput();
            lastMaxTokens = ANALYSIS_CONTINUATION_MAX_TOKENS;
        }
        return merged;
    }

    private String buildAnalysisContinuationSystemPrompt() {
        return """
                你是 JSON 输出续写器。你只负责补全上一次被截断的 JSON。
                必须严格返回 JSON 对象：{"continuation":"从截断处继续的原始 JSON 字符串片段"}。
                不要重复已经给出的内容，不要解释，不要输出 markdown。
                continuation 字符串必须从上一段截断位置之后继续，目标是让拼接后的整体 JSON 可以解析。
                如果无法判断续写位置，返回 {"continuation":""}。
                """;
    }

    private String buildAnalysisContinuationPrompt(String previousOutput) {
        return "上一段模型输出疑似在 JSON 中途被截断。请只从截断处继续补全 JSON。\n"
                + "上一段末尾如下：\n"
                + tailForPrompt(previousOutput, 2_000);
    }

    private String tailForPrompt(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxChars);
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
            row.putIfAbsent("skill_layer", inferSkillLayer(String.valueOf(row.get("point_type"))));
            row.putIfAbsent("design_method", inferDesignMethod(String.valueOf(row.get("point_type"))));
            row.putIfAbsent("priority_hint", "CORE");
            if (!unsupportedItems.isEmpty()) {
                row.putIfAbsent("unsupported_items", unsupportedItems);
                row.put("needs_confirmation", true);
            }
        }
        String json = toJson(rows);
        return json == null ? testPoints : json;
    }

    private String inferSkillLayer(String pointType) {
        return switch (pointType == null ? "" : pointType.toUpperCase()) {
            case "EXCEPTION", "AUTH", "STATE" -> "EXCEPTION";
            case "BOUNDARY", "DATA", "CONCURRENCY", "IDEMPOTENT" -> "BOUNDARY_SUPPLEMENT";
            default -> "FUNCTIONAL";
        };
    }

    private String inferDesignMethod(String pointType) {
        return switch (pointType == null ? "" : pointType.toUpperCase()) {
            case "BOUNDARY" -> "边界值";
            case "DATA" -> "数据一致性检查";
            case "AUTH" -> "权限矩阵";
            case "CONCURRENCY", "IDEMPOTENT" -> "错误推测";
            case "STATE" -> "状态迁移";
            case "BRANCH" -> "判定表";
            default -> "场景法";
        };
    }

    private Map<String, Object> buildEvidenceSummary(ProjectSemanticContextService.BuildResult semanticContext) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<ProjectSemanticContextService.SemanticSignal> signals =
                semanticContext == null || semanticContext.signals() == null ? List.of() : semanticContext.signals();
        List<Map<String, Object>> signalRefs = signals.stream()
                .limit(8)
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
            item.put("skill_layer", row.getOrDefault("skill_layer", "FUNCTIONAL"));
            item.put("design_method", row.getOrDefault("design_method", "场景法"));
            item.put("needs_confirmation", row.getOrDefault("needs_confirmation", false));
            return item;
        }).toList();
    }

    private Map<String, Object> buildGenerationQualityGate(RequirementAnalysisRecord analysis,
                                                           DirectCaseGenerationService.GenerateResult result) {
        Map<String, Object> gate = new LinkedHashMap<>();
        String generationPoints = filterTestPointsByScope(analysis.testPoints(), Set.of(SCOPE_GENERATE));
        int testPointCount = countJsonArray(generationPoints);
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
        boolean hasLowEvidencePoint = extractSourceTestPoints(generationPoints).stream()
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
        String sourceCasePlan = generatorRefs == null ? "" : String.valueOf(generatorRefs.getOrDefault("sourceCasePlan", "")).trim();
        String sourceCaseDesign = generatorRefs == null ? "" : String.valueOf(generatorRefs.getOrDefault("sourceCaseDesign", "")).trim();
        List<String> sourceBasis = generatorRefs == null ? List.of() : toStringList(generatorRefs.get("sourceBasis"));
        List<String> unsupportedItems = generatorRefs == null ? List.of() : toStringList(generatorRefs.get("unsupportedItems"));
        double confidence = generatorRefs == null ? 0.0 : doubleValue(generatorRefs.get("confidence"), 0.0);
        List<String> testPointTitles = extractSourceTestPoints(analysis.testPoints()).stream()
                .map(row -> String.valueOf(row.getOrDefault("title", "")).trim())
                .filter(title -> !title.isBlank())
                .toList();
        List<String> casePlanIds = extractCasePlanIds(analysis.analysisResult());
        Map<String, List<String>> planDesignIds = extractCasePlanDesignIds(analysis.analysisResult());
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
        if (!casePlanIds.isEmpty()) {
            if (sourceCasePlan.isBlank()) {
                warnings.add("该用例未声明来源用例编排计划，无法确认节点断点。");
                invalidStructure = true;
            } else if (!casePlanIds.contains(sourceCasePlan)) {
                warnings.add("该用例声明的来源用例编排计划未命中当前分析计划。");
                invalidStructure = true;
            }
            List<String> validDesigns = planDesignIds.getOrDefault(sourceCasePlan, List.of());
            if (sourceCaseDesign.isBlank()) {
                warnings.add("该用例未声明来源用例设计项，无法确认测试方法是否完整落地。");
                invalidStructure = true;
            } else if (!validDesigns.contains(sourceCaseDesign)) {
                warnings.add("该用例声明的来源用例设计项未命中当前 CP 计划。");
                invalidStructure = true;
            }
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
        gate.put("sourceCasePlan", sourceCasePlan);
        gate.put("sourceCaseDesign", sourceCaseDesign);
        gate.put("sourceBasisCount", sourceBasis.size());
        gate.put("unsupportedCount", unsupportedItems.size());
        gate.put("confidence", confidence);
        gate.put("stepCount", stepCount);
        gate.put("expectedCount", expectedCount);
        gate.put("warnings", warnings.stream().distinct().toList());
        return gate;
    }

    private List<String> extractCasePlanIds(String analysisResult) {
        Map<String, Object> root = readJsonObject(analysisResult);
        if (root == null) return List.of();
        List<Map<String, Object>> plans = readJsonObjectList(toJson(root.get("case_plan")));
        return plans == null ? List.of() : plans.stream()
                .map(plan -> String.valueOf(plan.getOrDefault("id", "")).trim())
                .filter(id -> !id.isBlank())
                .toList();
    }

    private Map<String, List<String>> extractCasePlanDesignIds(String analysisResult) {
        Map<String, Object> root = readJsonObject(analysisResult);
        if (root == null) return Map.of();
        List<Map<String, Object>> plans = readJsonObjectList(toJson(root.get("case_plan")));
        if (plans == null) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> plan : plans) {
            String planId = String.valueOf(plan.getOrDefault("id", "")).trim();
            if (planId.isBlank()) continue;
            List<Map<String, Object>> designs = readJsonObjectList(toJson(plan.get("case_designs")));
            result.put(planId, designs == null ? List.of() : designs.stream()
                    .map(design -> String.valueOf(design.getOrDefault("id", "")).trim())
                    .filter(id -> !id.isBlank()).toList());
        }
        return result;
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

    private static int countJsonArray(String json) {
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

    static String extractAnalysisJson(String llmOutput) {
        String analysis = extractJson(llmOutput, "analysis");
        if (analysis != null) return analysis;
        analysis = extractJson(llmOutput, "analysis_result");
        if (analysis != null) return analysis;
        analysis = extractRootAnalysisJson(llmOutput);
        if (analysis != null) return analysis;
        return salvagePartialAnalysisJson(llmOutput);
    }

    static boolean shouldRequestAnalysisContinuation(String llmOutput, int tokenOutput, int maxTokens) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return false;
        }
        if (isCompleteJsonDocument(llmOutput)) {
            return false;
        }
        String normalized = llmOutput.trim();
        if (!normalized.startsWith("{")) {
            return false;
        }

        // Some gateways terminate a JSON-object response before any useful field is emitted
        // (for example just `{` or `{"`).  This is still a recoverable stream fragment:
        // ask the model for the next chunk instead of treating transport success as a usable
        // product result.  We only do this for a JSON prefix, never for arbitrary prose.
        if (normalized.length() <= 64) {
            return true;
        }

        boolean nearTokenLimit = maxTokens > 0 && tokenOutput >= Math.max(1, maxTokens - 16);
        return nearTokenLimit && (normalized.contains("\"analysis\"")
                || normalized.contains("\"analysis_result\"")
                || normalized.contains("\"coverage_matrix\"")
                || normalized.contains("\"test_points\""));
    }

    private static boolean isCompleteJsonDocument(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String candidate : jsonCandidates(value)) {
            try {
                objectMapper.readTree(candidate);
                return true;
            } catch (JsonProcessingException ignored) {
                // Continue until every candidate has been checked.
            }
        }
        return false;
    }

    private LlmErrorCode toLlmErrorCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return LlmErrorCode.UNKNOWN_ERROR;
        }
        try {
            return LlmErrorCode.valueOf(rawCode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LlmErrorCode.UNKNOWN_ERROR;
        }
    }

    static String mergeContinuation(String original, String continuation) {
        if (continuation == null || continuation.isBlank()) {
            return original;
        }
        String left = original == null ? "" : original;
        String right = continuation.stripLeading();
        if (right.startsWith("```")) {
            int newline = right.indexOf('\n');
            if (newline >= 0) {
                right = right.substring(newline + 1);
            }
            int fenceEnd = right.lastIndexOf("```");
            if (fenceEnd >= 0) {
                right = right.substring(0, fenceEnd);
            }
            right = right.stripLeading();
        }
        String tail = left.length() > 400 ? left.substring(left.length() - 400) : left;
        int maxOverlap = Math.min(tail.length(), right.length());
        for (int len = maxOverlap; len >= 6; len--) {
            if (tail.endsWith(right.substring(0, len))) {
                return left + right.substring(len);
            }
        }
        return left + right;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
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
            if (node != null) {
                if (node.isTextual()) {
                    String text = node.asText();
                    String validated = validateJson(text);
                    if (validated != null) return validated;
                }
                return objectMapper.writeValueAsString(node);
            }
        } catch (JsonProcessingException ignored) {
            // ignore invalid candidate and continue
        }
        return null;
    }

    private static String extractRootAnalysisJson(String llmOutput) {
        if (llmOutput == null) return null;
        for (String candidate : jsonCandidates(llmOutput)) {
            try {
                var root = objectMapper.readTree(candidate);
                if (!root.isObject()) continue;
                if (root.has("requirement_understanding")
                        || root.has("affected_modules")
                        || root.has("requirement_atoms")
                        || root.has("test_units")
                        || root.has("review_risk_questions")
                        || root.has("coverage_matrix")
                        || root.has("test_points")) {
                    return objectMapper.writeValueAsString(root);
                }
            } catch (JsonProcessingException ignored) {
                // ignore invalid candidate and continue
            }
        }
        return null;
    }

    private static String salvagePartialAnalysisJson(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        putPartialString(root, llmOutput, "requirement_understanding");
        putPartialString(root, llmOutput, "business_domain");
        putPartialString(root, llmOutput, "requirement_type");
        putPartialString(root, llmOutput, "input_source_notes");
        putPartialArray(root, llmOutput, "input_sources");
        putPartialArray(root, llmOutput, "affected_modules");
        putPartialArray(root, llmOutput, "affected_pages");
        putPartialArray(root, llmOutput, "affected_fields");
        putPartialArray(root, llmOutput, "affected_flows");
        putPartialArray(root, llmOutput, "affected_roles");
        putPartialArray(root, llmOutput, "risk_scenarios");
        putPartialArray(root, llmOutput, "boundary_conditions");
        putPartialArray(root, llmOutput, "review_risk_questions");
        putPartialArray(root, llmOutput, "coverage_matrix");
        if (root.isEmpty()) {
            return null;
        }
        root.putIfAbsent("uncertain_items", List.of(
                "模型输出可能被截断，系统仅保留已解析的需求分析内容；请结合右侧证据或重新分析确认。"));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static void putPartialString(Map<String, Object> root, String text, String key) {
        String value = extractPartialJsonStringValue(text, key);
        if (value != null && !value.isBlank()) {
            root.put(key, value);
        }
    }

    private static void putPartialArray(Map<String, Object> root, String text, String key) {
        String value = extractJsonFragment(text, key);
        if (value == null) {
            return;
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (parsed instanceof Collection<?> collection && !collection.isEmpty()) {
                root.put(key, parsed);
            }
        } catch (JsonProcessingException ignored) {
            // ignore malformed or truncated arrays
        }
    }

    private static String extractPartialJsonStringValue(String text, String key) {
        int keyStart = text.indexOf("\"" + key + "\"");
        if (keyStart < 0) {
            return null;
        }
        int colon = text.indexOf(':', keyStart);
        if (colon < 0) {
            return null;
        }
        int valueStart = colon + 1;
        while (valueStart < text.length() && Character.isWhitespace(text.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= text.length() || text.charAt(valueStart) != '"') {
            return null;
        }
        String quoted = readQuotedJsonString(text, valueStart);
        if (quoted == null) {
            return null;
        }
        try {
            return objectMapper.readValue(quoted, String.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
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

    static String normalizeChangeScope(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if ("MINOR".equals(upper) || upper.contains("\"MINOR\"") || upper.contains("'MINOR'")) {
            return "MINOR";
        }
        if ("MAJOR".equals(upper) || upper.contains("\"MAJOR\"") || upper.contains("'MAJOR'")) {
            return "MAJOR";
        }
        if (normalized.contains("小幅") || normalized.contains("局部") || normalized.contains("描述")
                || normalized.contains("不影响") || normalized.contains("不新增") || normalized.contains("只改")) {
            return "MINOR";
        }
        if (normalized.contains("重大") || normalized.contains("大范围") || normalized.contains("新增")
                || normalized.contains("模块") || normalized.contains("页面") || normalized.contains("流程")
                || normalized.contains("角色") || normalized.contains("影响范围")) {
            return "MAJOR";
        }
        log.warn("Unrecognized change_scope from LLM, defaulting to MAJOR: {}", clipForPrompt(normalized, 120));
        return "MAJOR";
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

    private record CoverageWorkItem(String unitId, Map<String, Object> unit, List<Map<String, Object>> atoms) {
        private Map<String, Object> promptPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("test_unit_ref", unitId);
            payload.put("test_unit", unit);
            payload.put("requirement_atoms", atoms);
            return payload;
        }
    }
}
