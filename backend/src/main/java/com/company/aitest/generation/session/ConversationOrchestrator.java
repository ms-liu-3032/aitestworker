package com.company.aitest.generation.session;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final IntentRecognizer intentRecognizer;
    private final GenerationSessionService sessionService;
    private final GenerationMessageService messageService;
    private final RequirementAnalysisService analysisService;
    private final JdbcClient jdbc;

    public ConversationOrchestrator(IntentRecognizer intentRecognizer,
                                     GenerationSessionService sessionService,
                                     GenerationMessageService messageService,
                                     RequirementAnalysisService analysisService,
                                     GenerationAttachmentService attachmentService,
                                     JdbcClient jdbc) {
        this.intentRecognizer = intentRecognizer;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.analysisService = analysisService;
        this.jdbc = jdbc;
    }

    public ConversationReply processUserMessage(Long sessionId, String userContent, CurrentUser user) {
        return processUserMessage(sessionId, userContent, user, false);
    }

    public ConversationReply processUserMessageForAsyncTask(Long sessionId, String userContent, CurrentUser user) {
        return processUserMessage(sessionId, userContent, user, true);
    }

    public boolean isExplicitTomModeChoice(String content) {
        return intentRecognizer.isExplicitTomModeChoice(content);
    }

    private ConversationReply processUserMessage(Long sessionId, String userContent, CurrentUser user, boolean propagateAnalysisFailure) {
        var session = sessionService.get(null, sessionId, user);
        long lastMessageIdBefore = latestMessageId(sessionId);
        String stage = resolveStage(sessionId);
        if (isBusyStage(stage)) {
            var busyMsg = messageService.appendAssistantMessage(sessionId,
                    "当前会话仍在处理中，请等待这一轮完成后再继续补充。", null, "OPERATION_HINT", 0);
            return new ConversationReply(List.of(busyMsg), null);
        }
        UserIntent intent = intentRecognizer.recognize(userContent, stage);

        log.info("Session {} stage={} intent={} content={}", sessionId, stage, intent,
                userContent.substring(0, Math.min(50, userContent.length())));

        // Save user message
        messageService.appendUserMessage(sessionId, userContent, user);

        var reply = switch (intent) {
            case SUBMIT_REQUIREMENT -> handleSubmitRequirement(session, userContent, user);
            case CHOOSE_TOM_MODE -> handleChooseTomMode(session, userContent, user, propagateAnalysisFailure);
            case SUPPLEMENT_REQUIREMENT -> handleSupplementRequirement(session, userContent, user, propagateAnalysisFailure);
            case REANALYZE_REQUIREMENT -> handleReanalyzeRequirement(session, user, propagateAnalysisFailure);
            case CONFIRM_ANALYSIS -> handleConfirmAnalysis(session, user);
            case SKIP_CONFIRMATION, GENERATE_CASES -> handleSkipAndGenerate(session, user);
            default -> handleUnknown(sessionId);
        };
        return new ConversationReply(messageService.listMessagesSince(sessionId, lastMessageIdBefore), reply.analysis());
    }

    private ConversationReply handleSubmitRequirement(GenerationSessionRecord session, String content, CurrentUser user) {
        Long sessionId = session.id();

        if (session.latestAnalysisVersion() == 0) {
            String title = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            sessionService.updateTitle(session.projectId(), sessionId, title, user);
        }

        sessionService.updateStage(sessionId, "ASK_TOM_MODE");

        String reply = "收到你的需求。是否需要结合当前项目的 TOM 模型和已确认资产进行分析？\n\n" +
                "你可以回复：\n" +
                "- 使用 TOM（同时使用项目 TOM 和系统 TOM）\n" +
                "- 只使用项目 TOM\n" +
                "- 不使用 TOM，直接分析";

        var sysMsg = messageService.appendAssistantMessage(sessionId, reply, null, "SYSTEM_ASK_TOM_MODE", 0);
        return new ConversationReply(List.of(sysMsg), null);
    }

    private ConversationReply handleChooseTomMode(GenerationSessionRecord session, String content, CurrentUser user, boolean propagateAnalysisFailure) {
        Long sessionId = session.id();
        String tomMode = intentRecognizer.resolveTomMode(content);
        sessionService.updateConfig(session.projectId(), sessionId, session.modelConfigId(),
                session.promptTemplateId(), tomMode, user);
        sessionService.updateStage(sessionId, "REQUIREMENT_ANALYZING");

        String modeDesc = switch (tomMode) {
            case "DIRECT" -> "不使用 TOM，直接根据需求分析";
            case "PROJECT_TOM" -> "使用项目 TOM 进行分析";
            default -> "同时使用项目 TOM 和系统 TOM 进行分析";
        };

        var ackMsg = messageService.appendAssistantMessage(sessionId, "好的，" + modeDesc + "。正在分析需求...", null, "SYSTEM_ANALYSIS_START", 0);

        try {
            var analysis = propagateAnalysisFailure
                    ? analysisService.analyzeScopeOnly(sessionId, user)
                    : analysisService.analyze(sessionId, user);
            sessionService.updateStage(sessionId, stageAfterAnalysis(analysis));

            String analysisReply = formatAnalysisOutput(analysis);
            var sysMsg = messageService.appendAssistantMessage(sessionId, analysisReply,
                    analysis.analysisResult(), "REQUIREMENT_ANALYSIS_RESULT", analysis.version());

            String confirmPrompt = buildNextStepPrompt(analysis);
            var confirmMsg = messageService.appendAssistantMessage(sessionId, confirmPrompt, null, "CLARIFICATION_QUESTION", analysis.version());

            return new ConversationReply(List.of(ackMsg, sysMsg, confirmMsg), analysis);
        } catch (Exception e) {
            sessionService.updateStage(sessionId, "REQUIREMENT_INPUT");
            var errMsg = messageService.appendAssistantMessage(sessionId,
                    formatModelFailure("分析失败", e), null, "ERROR", 0);
            if (propagateAnalysisFailure) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new com.company.aitest.common.BusinessException(e.getMessage());
            }
            return new ConversationReply(List.of(ackMsg, errMsg), null);
        }
    }

    private ConversationReply handleSupplementRequirement(GenerationSessionRecord session, String content,
                                                          CurrentUser user, boolean scopeOnly) {
        Long sessionId = session.id();
        sessionService.updateStage(sessionId, "REANALYZING");

        var latest = analysisService.getLatestAnalysis(sessionId);
        boolean canIncremental = latest != null && analysisService.canIncrementalAnalyze(sessionId);
        if (canIncremental) {
            analysisService.submitAnswer(sessionId, -1, content, user);
        }
        String ackText = canIncremental
                ? "收到你的补充，正在增量更新分析..."
                : "收到你的补充，正在重新全量分析...";
        var ackMsg = messageService.appendAssistantMessage(sessionId, ackText, null, "SYSTEM_ANALYSIS_START", 0);

        try {
            var analysis = canIncremental
                    ? (scopeOnly ? analysisService.incrementalAnalyzeScopeOnly(sessionId, content, user)
                            : analysisService.incrementalAnalyze(sessionId, content, user))
                    : (scopeOnly ? analysisService.analyzeScopeOnly(sessionId, user)
                            : analysisService.analyze(sessionId, user));
            sessionService.updateStage(sessionId, stageAfterAnalysis(analysis));

            // 增量分析后自动为新测试点生成用例
            if (!scopeOnly && canIncremental && analysis.newCasesNeeded() != null
                    && !analysis.newCasesNeeded().isBlank() && !"[]".equals(analysis.newCasesNeeded())) {
                try {
                    analysisService.autoGenerateNewCases(sessionId, analysis, user);
                    // 刷新分析记录（auto-generate 可能更新了 status）
                    analysis = analysisService.getLatestAnalysis(sessionId);
                } catch (Exception genEx) {
                    log.warn("自动为新测试点生成用例失败: {}", genEx.getMessage());
                }
            }

            String analysisReply = formatAnalysisOutput(analysis);
            var sysMsg = messageService.appendAssistantMessage(sessionId, analysisReply,
                    analysis.analysisResult(), "REQUIREMENT_ANALYSIS_RESULT", analysis.version());

            String confirmPrompt = buildNextStepPrompt(analysis);
            var confirmMsg = messageService.appendAssistantMessage(sessionId, confirmPrompt, null, "CLARIFICATION_QUESTION", analysis.version());

            return new ConversationReply(List.of(ackMsg, sysMsg, confirmMsg), analysis);
        } catch (Exception e) {
            sessionService.updateStage(sessionId, scopeOnly ? "WAITING_REQUIREMENT_SCOPE" : "WAITING_USER_CONFIRMATION");
            var errMsg = messageService.appendAssistantMessage(sessionId,
                    formatModelFailure("分析失败", e), null, "ERROR", 0);
            if (scopeOnly) {
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new com.company.aitest.common.BusinessException(e.getMessage());
            }
            return new ConversationReply(List.of(ackMsg, errMsg), null);
        }
    }

    private ConversationReply handleReanalyzeRequirement(GenerationSessionRecord session, CurrentUser user,
                                                         boolean scopeOnly) {
        Long sessionId = session.id();
        var latest = analysisService.getLatestAnalysis(sessionId);
        if (latest == null) {
            var errMsg = messageService.appendAssistantMessage(sessionId, "还没有分析结果，请先输入需求。", null, "ERROR", 0);
            return new ConversationReply(List.of(errMsg), null);
        }

        sessionService.updateStage(sessionId, "REANALYZING");
        var ackMsg = messageService.appendAssistantMessage(sessionId, "正在基于当前需求和已补充信息重新分析...", null, "SYSTEM_ANALYSIS_START", latest.version());

        try {
            var analysis = scopeOnly ? analysisService.analyzeScopeOnly(sessionId, user)
                    : analysisService.analyze(sessionId, user);
            sessionService.updateStage(sessionId, stageAfterAnalysis(analysis));

            String analysisReply = formatAnalysisOutput(analysis);
            var sysMsg = messageService.appendAssistantMessage(sessionId, analysisReply,
                    analysis.analysisResult(), "REQUIREMENT_ANALYSIS_RESULT", analysis.version());

            String confirmPrompt = buildNextStepPrompt(analysis);
            var confirmMsg = messageService.appendAssistantMessage(sessionId, confirmPrompt, null, "CLARIFICATION_QUESTION", analysis.version());

            return new ConversationReply(List.of(ackMsg, sysMsg, confirmMsg), analysis);
        } catch (Exception e) {
            sessionService.updateStage(sessionId, scopeOnly ? "WAITING_REQUIREMENT_SCOPE" : "WAITING_USER_CONFIRMATION");
            var errMsg = messageService.appendAssistantMessage(sessionId,
                    formatModelFailure("重新分析失败", e), null, "ERROR", 0);
            if (scopeOnly) {
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new com.company.aitest.common.BusinessException(e.getMessage());
            }
            return new ConversationReply(List.of(ackMsg, errMsg), null);
        }
    }

    private ConversationReply handleConfirmAnalysis(GenerationSessionRecord session, CurrentUser user) {
        Long sessionId = session.id();
        var latest = analysisService.getLatestAnalysis(sessionId);
        if (latest == null) {
            var errMsg = messageService.appendAssistantMessage(sessionId, "还没有分析结果，请先输入需求。", null, "ERROR", 0);
            return new ConversationReply(List.of(errMsg), null);
        }
        if ("NEED_SCOPE_CONFIRMATION".equals(latest.status()) || "SCOPE_CONFIRMED".equals(latest.status())) {
            var msg = messageService.appendAssistantMessage(sessionId,
                    "请先在右侧【本期需求范围】中确认哪些内容本期生成、仅作参考或排除。范围确认完成后系统才会生成覆盖矩阵和测试点。",
                    null, "OPERATION_HINT", latest.version());
            return new ConversationReply(List.of(msg), latest);
        }
        if ("NEED_TEST_POINT_SCOPE_CONFIRMATION".equals(latest.status())
                || "TEST_POINT_SCOPE_CONFIRMED".equals(latest.status())) {
            var msg = messageService.appendAssistantMessage(sessionId,
                    "请先在右侧【测试点生成范围】完成确认并等待用例编排结束。编排完成后再输入“生成用例”。",
                    null, "OPERATION_HINT", latest.version());
            return new ConversationReply(List.of(msg), latest);
        }
        if (hasClarificationQuestions(latest)) {
            var msg = messageService.appendAssistantMessage(sessionId,
                    "当前分析仍有【需要澄清】的问题。请直接补充答案，系统会重新分析；如果确认按当前假设继续，请回复「生成用例」。",
                    null, "CLARIFICATION_QUESTION", latest.version());
            return new ConversationReply(List.of(msg), latest);
        }

        analysisService.confirmAnalysis(sessionId, latest.version());
        sessionService.updateStage(sessionId, "ANALYSIS_READY");

        var sysMsg = messageService.appendAssistantMessage(sessionId,
                "分析已确认。你可以回复「生成用例」来生成测试用例草稿，或继续补充需求。", null, "OPERATION_HINT", latest.version());
        return new ConversationReply(List.of(sysMsg), latest);
    }

    private ConversationReply handleSkipAndGenerate(GenerationSessionRecord session, CurrentUser user) {
        Long sessionId = session.id();
        var latest = analysisService.getLatestAnalysis(sessionId);
        if (latest == null) {
            var errMsg = messageService.appendAssistantMessage(sessionId, "还没有分析结果，请先输入需求。", null, "ERROR", 0);
            return new ConversationReply(List.of(errMsg), null);
        }

        if ("NEED_SCOPE_CONFIRMATION".equals(latest.status()) || "SCOPE_CONFIRMED".equals(latest.status())) {
            var msg = messageService.appendAssistantMessage(sessionId,
                    "当前尚未确认本期需求范围，不能跳过范围审核直接生成用例。请先在右侧完成需求范围确认。",
                    null, "OPERATION_HINT", latest.version());
            return new ConversationReply(List.of(msg), latest);
        }
        if ("NEED_TEST_POINT_SCOPE_CONFIRMATION".equals(latest.status())
                || "TEST_POINT_SCOPE_CONFIRMED".equals(latest.status())) {
            var msg = messageService.appendAssistantMessage(sessionId,
                    "当前尚未完成测试点范围确认或用例编排，不能直接生成草稿。请先完成右侧测试点范围审核。",
                    null, "OPERATION_HINT", latest.version());
            return new ConversationReply(List.of(msg), latest);
        }

        if (!"CONFIRMED".equals(latest.status()) && !"GENERATED".equals(latest.status())) {
            analysisService.skipAnalysis(sessionId, latest.version());
        }

        sessionService.updateStage(sessionId, "CASE_GENERATING");
        var genMsg = messageService.appendAssistantMessage(sessionId, "正在生成测试用例，请稍候...", null, "SYSTEM_CASE_GENERATING", 0);

        try {
            var result = analysisService.doGenerate(sessionId, user);
            sessionService.updateStage(sessionId, "CASE_READY");

            int draftCount = countDrafts(sessionId);
            var summaries = listDraftSummaries(sessionId);
            StringBuilder sb = new StringBuilder("用例生成完成！共生成 ").append(draftCount).append(" 条草稿用例：\n");
            for (String s : summaries) {
                sb.append("\n- ").append(s);
            }
            sb.append("\n\n你可以：\n- 继续补充需求后重新生成\n- 查看右侧草稿面板");
            var doneMsg = messageService.appendAssistantMessage(sessionId,
                    sb.toString(), null, "CASE_RESULT", latest.version());

            return new ConversationReply(List.of(genMsg, doneMsg), result);
        } catch (Exception e) {
            sessionService.updateStage(sessionId, "WAITING_USER_CONFIRMATION");
            var errMsg = messageService.appendAssistantMessage(sessionId,
                    formatModelFailure("用例生成失败", e), null, "ERROR", 0);
            return new ConversationReply(List.of(genMsg, errMsg), null);
        }
    }

    private ConversationReply handleUnknown(Long sessionId) {
        var sysMsg = messageService.appendAssistantMessage(sessionId,
                "我不太理解你的意思。你可以：\n- 输入需求描述\n- 回复是否使用 TOM\n- 回复「生成用例」生成测试用例\n- 补充或修改需求",
                null, "OPERATION_HINT", 0);
        return new ConversationReply(List.of(sysMsg), null);
    }

    private String resolveStage(Long sessionId) {
        try {
            var list = jdbc.sql("SELECT current_stage FROM generation_session WHERE id = :id")
                    .param("id", sessionId).query((rs, rowNum) -> rs.getString("current_stage")).list();
            return list.isEmpty() ? "REQUIREMENT_INPUT" : list.get(0);
        } catch (Exception e) {
            return "REQUIREMENT_INPUT";
        }
    }

    private boolean isBusyStage(String stage) {
        return "REQUIREMENT_ANALYZING".equals(stage)
                || "REANALYZING".equals(stage)
                || "CASE_GENERATING".equals(stage);
    }

    private String formatModelFailure(String title, Exception exception) {
        String raw = exception == null || exception.getMessage() == null
                ? "未知错误"
                : exception.getMessage();
        StringBuilder sb = new StringBuilder(title).append(": ").append(raw);
        String lower = raw.toLowerCase();

        if (raw.contains("HTTP 524")) {
            sb.append("\n\n原因判断：模型中转网关等待上游响应超时，不是数据库或前端错误。");
            sb.append("\n建议处理：先用一条很短的需求验证当前模型配置；短需求也失败时更换 endpoint/模型，短需求正常但长需求失败时减少本轮需求或 TOM 上下文。");
        } else if (raw.contains("HTTP 429")) {
            sb.append("\n\n原因判断：模型服务限流或额度不足。");
            sb.append("\n建议处理：稍后重试，或检查模型服务额度、并发限制和账号套餐。");
        } else if (raw.contains("HTTP 401") || raw.contains("HTTP 403")) {
            sb.append("\n\n原因判断：模型服务拒绝访问。");
            sb.append("\n建议处理：检查 API Key、模型权限、余额和 endpoint 是否匹配。");
        } else if (raw.contains("HTTP 500") || raw.contains("HTTP 502")
                || raw.contains("HTTP 503") || raw.contains("HTTP 504")) {
            sb.append("\n\n原因判断：模型服务或中转网关异常。");
            sb.append("\n建议处理：稍后重试；如果持续出现，切换模型 endpoint 或查看中转服务状态。");
        } else if (raw.contains("未产出最终正文") || raw.contains("推理或输出预算已耗尽")) {
            sb.append("\n\n原因判断：模型接口格式正常，但模型只返回了推理通道，未输出最终 JSON 正文。");
            sb.append("\n建议处理：系统会优先按缺失字段缩小补齐节点；若持续出现，请在模型侧降低推理强度、提高输出额度或更换非推理模型。");
        } else if (raw.contains("模型返回解析失败") || raw.contains("模型返回内容为空")) {
            sb.append("\n\n原因判断：模型服务返回了 HTTP 成功响应，但没有可用正文；这不等同于 endpoint 不兼容。");
            sb.append("\n建议处理：查看 llm_invocation_log 中的 finish reason、输出摘要与模型配置；短请求也复现时再检查 endpoint/模型兼容性。");
        } else if (raw.contains("模型调用超时") || lower.contains("timed out") || lower.contains("timeout")) {
            sb.append("\n\n原因判断：模型在超时时间内没有返回。");
            sb.append("\n建议处理：稍后重试；若只在长需求失败，减少输入范围或关闭 TOM 后验证。");
        } else if (raw.contains("网络异常")) {
            sb.append("\n\n原因判断：后端到模型服务之间存在网络连接异常。");
            sb.append("\n建议处理：检查服务器网络、DNS、代理、防火墙和模型 endpoint 可达性。");
        } else if (raw.contains("模型配置缺少") || raw.contains("模型配置不存在")
                || raw.contains("模型配置未启用") || raw.contains("模型名称不能为空")) {
            sb.append("\n\n原因判断：模型配置不完整或未启用。");
            sb.append("\n建议处理：到模型配置页检查模型名称、API Key、endpoint 和启用状态。");
        } else {
            sb.append("\n\n建议处理：查看后端 Docker 日志和 llm_invocation_log，确认模型服务返回的具体错误。");
        }
        return sb.toString();
    }

    private long latestMessageId(Long sessionId) {
        try {
            return jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM generation_message WHERE session_id = :sid")
                    .param("sid", sessionId)
                    .query(Long.class)
                    .single();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String formatAnalysisOutput(RequirementAnalysisRecord analysis) {
        StringBuilder sb = new StringBuilder();
        String versionLabel = analysis.subVersion() > 0
                ? analysis.version() + "." + analysis.subVersion()
                : String.valueOf(analysis.version());
        sb.append("【需求分析结果 v").append(versionLabel).append("】\n\n");
        boolean appendedClarifications = false;

        if (analysis.analysisResult() != null) {
            try {
                JsonNode root = objectMapper.readTree(analysis.analysisResult());
                if (root.has("requirement_understanding")) {
                    sb.append("【需求理解】\n").append(root.get("requirement_understanding").asText()).append("\n\n");
                }
                if (root.has("affected_modules") && root.get("affected_modules").isArray()) {
                    sb.append("【影响范围】\n");
                    appendIfNotEmpty(sb, "模块", root, "affected_modules");
                    appendIfNotEmpty(sb, "页面", root, "affected_pages");
                    appendIfNotEmpty(sb, "字段", root, "affected_fields");
                    appendIfNotEmpty(sb, "流程", root, "affected_flows");
                    appendIfNotEmpty(sb, "角色", root, "affected_roles");
                    sb.append("\n");
                }
                if (root.has("conflicts") && root.get("conflicts").isArray() && root.get("conflicts").size() > 0) {
                    sb.append("【冲突项】\n");
                    root.get("conflicts").forEach(c -> sb.append("- ").append(c.asText()).append("\n"));
                    sb.append("\n");
                }
                // 评审前需确认问题（在影响范围之后、需要澄清之前）
                if (root.has("review_risk_questions") && root.get("review_risk_questions").isArray()
                        && root.get("review_risk_questions").size() > 0) {
                    appendReviewRiskQuestions(sb, root.get("review_risk_questions"));
                }
                if (root.has("clarification_questions") && root.get("clarification_questions").isArray()
                        && root.get("clarification_questions").size() > 0) {
                    appendClarificationQuestions(sb, root.get("clarification_questions"));
                    appendedClarifications = true;
                }
                if (root.has("uncertain_items") && root.get("uncertain_items").isArray() && root.get("uncertain_items").size() > 0) {
                    sb.append("【分析不确定项】\n");
                    root.get("uncertain_items").forEach(c -> sb.append("- ").append(c.asText()).append("\n"));
                    sb.append("\n");
                }
            } catch (Exception ignored) {}
        }
        if (!appendedClarifications && analysis.clarificationQuestions() != null
                && !analysis.clarificationQuestions().isBlank() && !"[]".equals(analysis.clarificationQuestions())) {
            try {
                JsonNode questions = objectMapper.readTree(analysis.clarificationQuestions());
                if (questions.isArray() && questions.size() > 0) {
                    appendClarificationQuestions(sb, questions);
                }
            } catch (Exception ignored) {}
        }

        if (analysis.testPoints() != null) {
            try {
                JsonNode tps = objectMapper.readTree(analysis.testPoints());
                if (tps.isArray() && tps.size() > 0) {
                    sb.append("【测试点分析】\n");
                    for (int i = 0; i < tps.size(); i++) {
                        JsonNode tp = tps.get(i);
                        sb.append(i + 1).append(". ").append(tp.has("title") ? tp.get("title").asText() : "?");
                        if (tp.has("description")) sb.append(" — ").append(tp.get("description").asText());
                        sb.append("\n");
                        // 展示 point_type 和 priority_hint
                        String pointType = tp.has("point_type") ? tp.get("point_type").asText("") : "";
                        String priorityHint = tp.has("priority_hint") ? tp.get("priority_hint").asText("") : "";
                        if (!pointType.isBlank() || !priorityHint.isBlank()) {
                            sb.append("   [");
                            if (!pointType.isBlank()) sb.append(pointType);
                            if (!pointType.isBlank() && !priorityHint.isBlank()) sb.append("/");
                            if (!priorityHint.isBlank()) sb.append(priorityHint);
                            sb.append("]\n");
                        }
                    }
                    sb.append("\n");
                }
            } catch (Exception ignored) {}
        }

        if (analysis.tomScopeSnapshot() != null && !analysis.tomScopeSnapshot().isBlank()) {
            sb.append("【TOM 参考】\n已结合 TOM 模型分析。\n\n");
        }

        return sb.toString().trim();
    }

    private void appendReviewRiskQuestions(StringBuilder sb, JsonNode questions) {
        sb.append("【评审前需确认问题】\n");
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i);
            String question = q.has("question") ? q.get("question").asText("") : "";
            if (question.isBlank()) continue;
            sb.append("- ").append(question).append("\n");
            if (q.has("reason") && !q.get("reason").asText("").isBlank()) {
                sb.append("  原因：").append(q.get("reason").asText()).append("\n");
            }
            if (q.has("impact") && !q.get("impact").asText("").isBlank()) {
                sb.append("  影响：").append(q.get("impact").asText()).append("\n");
            }
        }
        sb.append("\n");
    }

    private void appendClarificationQuestions(StringBuilder sb, JsonNode questions) {
        sb.append("【需要澄清】\n");
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i);
            if (q.isTextual()) {
                sb.append("- ").append(q.asText()).append("\n");
                continue;
            }
            String question = q.has("question") ? q.get("question").asText("") : "";
            if (question.isBlank()) {
                continue;
            }
            sb.append("- ").append(question).append("\n");
            if (q.has("reason") && !q.get("reason").asText("").isBlank()) {
                sb.append("  原因：").append(q.get("reason").asText()).append("\n");
            }
            if (q.has("impact") && !q.get("impact").asText("").isBlank()) {
                sb.append("  影响：").append(q.get("impact").asText()).append("\n");
            }
        }
        sb.append("\n");
    }

    private String buildConfirmPrompt(RequirementAnalysisRecord analysis) {
        if (hasClarificationQuestions(analysis)) {
            return "\n\n上方【需要澄清】会影响测试范围和用例设计，请直接输入补充说明；系统会基于你的补充重新分析。\n如果确认按当前假设继续，也可以回复「生成用例」。";
        }
        return "\n\n请确认以上分析是否正确。有什么需要补充或修改的内容可以直接输入。\n如果无误，可以回复「生成用例」。";
    }

    private String buildNextStepPrompt(RequirementAnalysisRecord analysis) {
        if (analysis != null && "NEED_SCOPE_CONFIRMATION".equals(analysis.status())) {
            return "\n\n请先在右侧【本期需求范围】中确认哪些需求需要生成测试点、哪些仅作参考或排除。"
                    + "确认后系统会继续生成覆盖矩阵和测试点；范围确认前不会展开后续模型节点。";
        }
        return buildConfirmPrompt(analysis);
    }

    private String stageAfterAnalysis(RequirementAnalysisRecord analysis) {
        return analysis != null && "NEED_SCOPE_CONFIRMATION".equals(analysis.status())
                ? "WAITING_REQUIREMENT_SCOPE" : "WAITING_USER_CONFIRMATION";
    }

    private boolean hasClarificationQuestions(RequirementAnalysisRecord analysis) {
        if (analysis == null) {
            return false;
        }
        if (hasNonEmptyQuestionArray(analysis.clarificationQuestions())) {
            return true;
        }
        if (analysis.analysisResult() == null || analysis.analysisResult().isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(analysis.analysisResult());
            return root.has("clarification_questions")
                    && root.get("clarification_questions").isArray()
                    && root.get("clarification_questions").size() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasNonEmptyQuestionArray(String value) {
        if (value == null || value.isBlank() || "[]".equals(value)) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isArray() && node.size() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void appendIfNotEmpty(StringBuilder sb, String label, JsonNode root, String field) {
        if (root.has(field) && root.get(field).isArray() && root.get(field).size() > 0) {
            sb.append("- ").append(label).append("：");
            root.get(field).forEach(n -> sb.append(n.asText()).append("、"));
            sb.setLength(sb.length() - 1);
            sb.append("\n");
        }
    }

    private int countDrafts(Long sessionId) {
        try {
            return jdbc.sql("SELECT COUNT(*) FROM test_case_draft WHERE session_id = :sid")
                    .param("sid", sessionId).query(Integer.class).single();
        } catch (Exception e) {
            return 0;
        }
    }

    private List<String> listDraftSummaries(Long sessionId) {
        try {
            return jdbc.sql("SELECT case_no, case_title, priority FROM test_case_draft WHERE session_id = :sid ORDER BY id")
                    .param("sid", sessionId).query((rs, rowNum) ->
                            rs.getString("case_no") + " " + rs.getString("case_title") + " [" + rs.getString("priority") + "]"
                    ).list();
        } catch (Exception e) {
            return List.of();
        }
    }

    public record ConversationReply(List<GenerationMessageRecord> newMessages, RequirementAnalysisRecord analysis) {}
}
