package com.company.aitest.generation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import com.company.aitest.minitom.MiniTomService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 生成反问服务。
 * <p>
 * 利用已有的 generation_question 表，在生成测试点/用例前检查是否需要反问。
 */
@Service
public class ClarificationService {

    private static final Logger log = LoggerFactory.getLogger(ClarificationService.class);

    private static final String CLARIFICATION_SYSTEM_PROMPT = """
            你是资深测试分析师。你的任务是判断当前需求描述是否足够清晰以生成测试点和测试用例。

            请检查以下方面：
            1. 需求是否缺少关键信息（字段是否必填、状态流转、权限控制等）
            2. TOM 命中结果中是否存在冲突（项目级与系统级描述不同）
            3. 是否只命中了系统级 TOM 而没有项目级 TOM（说明项目可能缺少本地验证）
            4. 轨迹摘要中是否有待确认事项
            5. 需求涉及的流程是否有不明确的分支

            你必须返回一个 JSON 对象：
            {
              "need_clarification": true/false,
              "questions": [
                {
                  "question": "具体问题",
                  "reason": "为什么要问这个问题",
                  "related_tom_ids": [1, 2],
                  "impact": "如果不回答会有什么影响"
                }
              ]
            }

            如果需求已经足够清晰，返回 {"need_clarification": false, "questions": []}
            不要输出任何额外解释，不要输出 markdown。
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final LlmGateway llmGateway;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public ClarificationService(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                 LlmGateway llmGateway, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.llmGateway = llmGateway;
        this.timeProvider = timeProvider;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 生成反问。返回 null 表示不需要反问。
     */
    public ClarificationResult generateQuestions(Long taskId, String requirementText,
                                                  MiniTomService.TestScopeResult scope,
                                                  Long modelConfigId, CurrentUser user) {
        if (modelConfigId == null) {
            log.info("ClarificationService: 无模型配置，跳过反问");
            return null;
        }

        // 构建上下文
        String tomContext = buildTomContext(scope);
        String conflictContext = buildConflictContext(scope);

        String userPrompt = """
                【需求描述】
                %s

                【TOM 匹配结果】
                %s
                %s

                请判断是否需要反问。
                """.formatted(requirementText, tomContext, conflictContext);

        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), null, taskId,
                "CLARIFICATION", LlmStage.TEST_CASE_GEN,
                modelConfigId, null, null, Map.of(),
                CLARIFICATION_SYSTEM_PROMPT, userPrompt, null));

        if (resp.status() != LlmInvocationStatus.OK) {
            log.warn("反问生成 LLM 调用失败: {}", resp.errorMessage());
            return null;
        }

        return parseAndSaveQuestions(taskId, resp.content(), user);
    }

    private ClarificationResult parseAndSaveQuestions(Long taskId, String rawOutput, CurrentUser user) {
        String jsonText = extractJson(rawOutput);
        try {
            Map<String, Object> result = objectMapper.readValue(jsonText, new TypeReference<>() {});
            Boolean needClarification = (Boolean) result.get("need_clarification");
            if (needClarification == null || !needClarification) {
                return new ClarificationResult(false, List.of());
            }

            List<Map<String, Object>> questionsRaw = (List<Map<String, Object>>) result.get("questions");
            if (questionsRaw == null || questionsRaw.isEmpty()) {
                return new ClarificationResult(false, List.of());
            }

            LocalDateTime now = timeProvider.now();
            List<ClarificationQuestion> questions = new ArrayList<>();
            int roundNo = getNextRoundNo(taskId);

            for (Map<String, Object> q : questionsRaw) {
                String questionText = String.valueOf(q.getOrDefault("question", ""));
                String reason = String.valueOf(q.getOrDefault("reason", ""));
                String impact = String.valueOf(q.getOrDefault("impact", ""));
                List<Long> relatedTomIds = (List<Long>) q.getOrDefault("related_tom_ids", List.of());
                String relatedTomJson = objectMapper.writeValueAsString(relatedTomIds);

                jdbcTemplate.update("""
                        INSERT INTO generation_question(
                            task_id, round_no, stage, question_text, question_reason,
                            question_source, question_level, options_json,
                            answer_text, answer_status, created_at, updated_at
                        ) VALUES (?, ?, 'CLARIFICATION', ?, ?, 'LLM', 'NORMAL', ?, NULL, 'PENDING', ?, ?)
                        """, taskId, roundNo, questionText, reason,
                        relatedTomJson, now, now);

                Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
                questions.add(new ClarificationQuestion(id, questionText, reason, impact, relatedTomIds, "PENDING", null));
            }

            return new ClarificationResult(true, questions);
        } catch (Exception e) {
            log.warn("解析反问结果失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 回答反问。
     */
    public ClarificationQuestion answerQuestion(Long questionId, String answerText, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE generation_question SET
                    answer_text = ?, answer_status = 'ANSWERED',
                    answered_by = ?, answered_at = ?, updated_at = ?
                WHERE id = ?
                """, answerText, user.id(), now, now, questionId);
        return getQuestion(questionId);
    }

    /**
     * 跳过反问（标记所有 PENDING 为 SKIPPED）。
     */
    public List<String> skipClarification(Long taskId, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE generation_question SET
                    answer_status = 'SKIPPED', answered_by = ?, answered_at = ?, updated_at = ?
                WHERE task_id = ? AND answer_status = 'PENDING'
                """, user.id(), now, now, taskId);

        // 返回假设列表
        List<String> assumptions = new ArrayList<>();
        assumptions.add("用户跳过了反问，以下内容基于当前上下文推断，可能不完全准确");
        List<ClarificationQuestion> pending = listQuestions(taskId);
        for (ClarificationQuestion q : pending) {
            if ("SKIPPED".equals(q.answerStatus())) {
                assumptions.add("假设：" + q.question());
            }
        }
        return assumptions;
    }

    /**
     * 列出任务的所有反问。
     */
    public List<ClarificationQuestion> listQuestions(Long taskId) {
        return jdbc.sql("""
                SELECT id, question_text, question_reason, question_source,
                       options_json, answer_text, answer_status
                FROM generation_question WHERE task_id = :taskId
                ORDER BY round_no, id
                """).param("taskId", taskId)
                .query((rs, rowNum) -> {
                    String optionsJson = rs.getString("options_json");
                    List<Long> relatedTomIds = List.of();
                    if (optionsJson != null && !optionsJson.isBlank()) {
                        try {
                            relatedTomIds = objectMapper.readValue(optionsJson, new TypeReference<>() {});
                        } catch (Exception ignored) {}
                    }
                    return new ClarificationQuestion(
                            rs.getLong("id"),
                            rs.getString("question_text"),
                            rs.getString("question_reason"),
                            "",
                            relatedTomIds,
                            rs.getString("answer_status"),
                            rs.getString("answer_text"));
                }).list();
    }

    /**
     * 检查是否有未回答的反问。
     */
    public boolean hasPendingQuestions(Long taskId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM generation_question
                WHERE task_id = :taskId AND answer_status = 'PENDING'
                """).param("taskId", taskId).query(Integer.class).single();
        return count != null && count > 0;
    }

    private ClarificationQuestion getQuestion(Long id) {
        return jdbc.sql("""
                SELECT id, question_text, question_reason, question_source,
                       options_json, answer_text, answer_status
                FROM generation_question WHERE id = :id
                """).param("id", id)
                .query((rs, rowNum) -> {
                    String optionsJson = rs.getString("options_json");
                    List<Long> relatedTomIds = List.of();
                    if (optionsJson != null && !optionsJson.isBlank()) {
                        try {
                            relatedTomIds = objectMapper.readValue(optionsJson, new TypeReference<>() {});
                        } catch (Exception ignored) {}
                    }
                    return new ClarificationQuestion(
                            rs.getLong("id"),
                            rs.getString("question_text"),
                            rs.getString("question_reason"),
                            "",
                            relatedTomIds,
                            rs.getString("answer_status"),
                            rs.getString("answer_text"));
                }).single();
    }

    private int getNextRoundNo(Long taskId) {
        Integer max = jdbc.sql("""
                SELECT MAX(round_no) FROM generation_question WHERE task_id = :taskId
                """).param("taskId", taskId).query(Integer.class).single();
        return (max != null ? max : 0) + 1;
    }

    private String buildTomContext(MiniTomService.TestScopeResult scope) {
        if (scope == null) return "（无 TOM 匹配结果）";
        StringBuilder sb = new StringBuilder();
        sb.append("项目级 TOM: %d 个, 系统级 TOM: %d 个\n".formatted(scope.projectTomCount, scope.systemTomCount));
        if (!scope.affectedModules.isEmpty()) sb.append("影响模块: ").append(names(scope.affectedModules)).append("\n");
        if (!scope.affectedPages.isEmpty()) sb.append("影响页面: ").append(names(scope.affectedPages)).append("\n");
        if (!scope.affectedFields.isEmpty()) sb.append("影响字段: ").append(names(scope.affectedFields)).append("\n");
        if (!scope.affectedFlows.isEmpty()) sb.append("影响流程: ").append(names(scope.affectedFlows)).append("\n");
        if (!scope.affectedStates.isEmpty()) sb.append("影响状态: ").append(names(scope.affectedStates)).append("\n");
        return sb.toString();
    }

    private String buildConflictContext(MiniTomService.TestScopeResult scope) {
        if (scope == null || scope.conflicts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n【TOM 冲突】\n");
        for (var c : scope.conflicts) {
            sb.append("- %s: %s\n".formatted(c.conflictType(), c.description()));
        }
        return sb.toString();
    }

    private String names(List<?> items) {
        List<String> names = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof com.company.aitest.minitom.TestObjectModelRecord tom) {
                names.add(tom.name());
            }
        }
        return String.join(", ", names);
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
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

    public record ClarificationResult(boolean needClarification, List<ClarificationQuestion> questions) {}

    public record ClarificationQuestion(
            Long id, String question, String reason, String impact,
            List<Long> relatedTomIds, String answerStatus, String answerText) {}
}
