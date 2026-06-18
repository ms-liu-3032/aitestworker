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
import com.company.aitest.minitom.TestObjectModelRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 测试点生成服务。
 */
@Service
public class TestPointGenerationService {

    private static final String SYSTEM_PROMPT = """
            你是资深测试工程师。请根据输入需求和 TOM 上下文生成测试点草稿。

            你必须只返回 JSON 数组，不要输出任何额外解释，不要输出 markdown。
            数组每个对象字段固定为：
            moduleName, pointContent, testType, designMethod, suggestedPriority, isAssumption, assumptionNote

            其中：
            - testType: 功能测试 / 边界测试 / 异常测试 / 兼容性测试 / 安全测试 / 性能测试
            - designMethod: 等价类 / 边界值 / 场景法 / 判定表 / 因果图 / 错误推测
            - suggestedPriority: P0 / P1 / P2 / P3
            - isAssumption: 0 或 1（如果是基于假设的测试点，标记为 1）
            - assumptionNote: 如果 isAssumption=1，说明假设内容
            """;

    private final GenerationTaskService generationTaskService;
    private final ClarificationService clarificationService;
    private final LlmGateway llmGateway;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final MiniTomService miniTomService;

    public TestPointGenerationService(GenerationTaskService generationTaskService,
                                       ClarificationService clarificationService,
                                       LlmGateway llmGateway,
                                       JdbcTemplate jdbcTemplate, JdbcClient jdbc,
                                       TimeProvider timeProvider,
                                       MiniTomService miniTomService) {
        this.generationTaskService = generationTaskService;
        this.clarificationService = clarificationService;
        this.llmGateway = llmGateway;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.timeProvider = timeProvider;
        this.objectMapper = new ObjectMapper();
        this.miniTomService = miniTomService;
    }

    /**
     * 生成测试点。
     */
    public TestPointGenerateResult generateTestPoints(Long projectId, Long taskId, CurrentUser user) {
        GenerationTaskRecord task = generationTaskService.get(projectId, taskId);
        if (task.modelConfigId() == null) {
            throw new BusinessException("任务未配置模型，无法生成测试点");
        }

        // 检查是否有未回答的反问
        if (clarificationService.hasPendingQuestions(taskId)) {
            List<ClarificationService.ClarificationQuestion> questions = clarificationService.listQuestions(taskId);
            List<ClarificationService.ClarificationQuestion> pending = questions.stream()
                    .filter(q -> "PENDING".equals(q.answerStatus()))
                    .toList();
            return new TestPointGenerateResult(taskId, true, pending, null, null);
        }

        // 构建 TOM 上下文
        String tomContext = "";
        if (Boolean.TRUE.equals(task.useMiniTom())) {
            String scopeSnapshot = task.testScopeSnapshot();
            if (scopeSnapshot == null || scopeSnapshot.isBlank()) {
                // 快照不存在，主动构建 TOM 范围并持久化
                MiniTomService.TestScopeResult scope = miniTomService.buildTestScope(
                        projectId, task.requirementText(), task.modelConfigId(), user);
                persistTomSnapshot(taskId, scope);
                scopeSnapshot = serializeScope(scope);
            }
            if (scopeSnapshot != null && !scopeSnapshot.isBlank()) {
                tomContext = "\n【TOM 上下文】\n" + scopeSnapshot;
            }
        }

        // 构建反问回答上下文
        String clarificationContext = buildClarificationContext(taskId);

        // 构建假设上下文
        String assumptionContext = "";
        if (task.assumptionsSnapshot() != null && !task.assumptionsSnapshot().isBlank()) {
            assumptionContext = "\n【假设说明】\n" + task.assumptionsSnapshot();
        }

        String userPrompt = """
                【需求描述】
                %s
                %s
                %s
                %s
                请生成测试点草稿。
                """.formatted(
                        emptySafe(task.requirementText()),
                        tomContext,
                        clarificationContext,
                        assumptionContext);

        LlmInvocationResponse resp = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, taskId,
                "TEST_POINT_GEN", LlmStage.TEST_POINT_GEN,
                task.modelConfigId(), null, null, Map.of(),
                SYSTEM_PROMPT, userPrompt, null, 16384));

        if (resp.status() != LlmInvocationStatus.OK) {
            throw new BusinessException("测试点生成失败：" + emptySafe(resp.errorMessage()));
        }

        List<TestPointDraft> drafts = parseAndSave(taskId, projectId, resp.content(), user);
        return new TestPointGenerateResult(taskId, false, List.of(), resp.content(), drafts);
    }

    /**
     * 列出任务的测试点草稿。
     */
    public List<TestPointDraftView> listDrafts(Long projectId, Long taskId) {
        generationTaskService.get(projectId, taskId);
        return jdbc.sql("""
                SELECT id, task_id, project_id, module_name, point_content,
                       test_type, design_method, suggested_priority,
                       is_assumption, assumption_note, status, created_at
                FROM test_point_draft
                WHERE project_id = :projectId AND task_id = :taskId
                ORDER BY id ASC
                """)
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new TestPointDraftView(
                        rs.getLong("id"),
                        rs.getLong("task_id"),
                        rs.getString("module_name"),
                        rs.getString("point_content"),
                        rs.getString("test_type"),
                        rs.getString("design_method"),
                        rs.getString("suggested_priority"),
                        rs.getBoolean("is_assumption"),
                        rs.getString("assumption_note"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    private List<TestPointDraft> parseAndSave(Long taskId, Long projectId, String rawOutput, CurrentUser user) {
        String jsonText = extractJson(rawOutput);
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(jsonText, new TypeReference<>() {});
            List<TestPointDraft> drafts = new ArrayList<>();
            LocalDateTime now = timeProvider.now();

            for (Map<String, Object> row : rows) {
                String moduleName = str(row.get("moduleName"), "默认模块");
                String pointContent = str(row.get("pointContent"), "");
                String testType = str(row.get("testType"), "功能测试");
                String designMethod = str(row.get("designMethod"), "场景法");
                String suggestedPriority = normalizePriority(str(row.get("suggestedPriority"), "P2"));
                int isAssumption = row.get("isAssumption") instanceof Number ? ((Number) row.get("isAssumption")).intValue() : 0;
                String assumptionNote = str(row.get("assumptionNote"), "");

                jdbcTemplate.update("""
                        INSERT INTO test_point_draft(
                            task_id, project_id, module_name, point_content,
                            test_type, design_method, suggested_priority,
                            is_assumption, assumption_note, assumption_confirm_status,
                            status, created_by, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'DRAFT', ?, ?, ?)
                        """, taskId, projectId, moduleName, pointContent,
                        testType, designMethod, suggestedPriority,
                        isAssumption, assumptionNote,
                        user.id(), now, now);

                Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
                drafts.add(new TestPointDraft(id, moduleName, pointContent, testType, designMethod, suggestedPriority, isAssumption, assumptionNote));
            }

            if (drafts.isEmpty()) {
                throw new BusinessException("模型未返回可用测试点");
            }
            return drafts;
        } catch (JsonProcessingException ex) {
            return List.of(new TestPointDraft(null, "默认模块", rawOutput, "功能测试", "场景法", "P2", 1, "模型返回原文，待人工整理"));
        }
    }

    private void persistTomSnapshot(Long taskId, MiniTomService.TestScopeResult scope) {
        try {
            String testScopeJson = objectMapper.writeValueAsString(scope);
            String projectTomJson = null;
            String systemTomJson = null;
            var projectToms = scope.tomEvidence.stream().filter(e -> "PROJECT".equals(e.scope())).toList();
            var systemToms = scope.tomEvidence.stream().filter(e -> "SYSTEM".equals(e.scope())).toList();
            if (!projectToms.isEmpty()) projectTomJson = objectMapper.writeValueAsString(projectToms);
            if (!systemToms.isEmpty()) systemTomJson = objectMapper.writeValueAsString(systemToms);

            int tomHitCount = 0;
            if (scope.affectedModules != null) tomHitCount += scope.affectedModules.size();
            if (scope.affectedPages != null) tomHitCount += scope.affectedPages.size();
            if (scope.affectedFields != null) tomHitCount += scope.affectedFields.size();
            if (scope.affectedRoles != null) tomHitCount += scope.affectedRoles.size();
            if (scope.affectedFlows != null) tomHitCount += scope.affectedFlows.size();
            if (scope.affectedStates != null) tomHitCount += scope.affectedStates.size();
            if (scope.suggestedAssertions != null) tomHitCount += scope.suggestedAssertions.size();

            jdbcTemplate.update("""
                    UPDATE generation_task SET
                        test_scope_snapshot = ?, project_tom_snapshot = ?,
                        system_tom_snapshot = ?, tom_hit_count = ?, updated_at = ?
                    WHERE id = ?
                    """, testScopeJson, projectTomJson, systemTomJson, tomHitCount,
                    timeProvider.now(), taskId);
        } catch (JsonProcessingException e) {
            // 非致命，日志已在上层
        }
    }

    private String serializeScope(MiniTomService.TestScopeResult scope) {
        try {
            return objectMapper.writeValueAsString(scope);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String buildClarificationContext(Long taskId) {
        List<ClarificationService.ClarificationQuestion> questions = clarificationService.listQuestions(taskId);
        if (questions.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n【已回答的反问】\n");
        for (var q : questions) {
            if ("ANSWERED".equals(q.answerStatus()) && q.answerText() != null) {
                sb.append("问：%s\n答：%s\n\n".formatted(q.question(), q.answerText()));
            }
        }
        return sb.toString();
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

    private String normalizePriority(String value) {
        if (value == null) return "P2";
        String v = value.trim().toUpperCase();
        return switch (v) {
            case "P0", "P1", "P2", "P3", "P4" -> v;
            default -> "P2";
        };
    }

    private String emptySafe(String value) {
        return value == null ? "" : value;
    }

    public record TestPointDraft(Long id, String moduleName, String pointContent, String testType,
                                  String designMethod, String suggestedPriority,
                                  int isAssumption, String assumptionNote) {}

    public record TestPointDraftView(Long id, Long taskId, String moduleName, String pointContent,
                                      String testType, String designMethod, String suggestedPriority,
                                      boolean isAssumption, String assumptionNote, String status,
                                      LocalDateTime createdAt) {}

    public record TestPointGenerateResult(Long taskId, boolean needClarification,
                                           List<ClarificationService.ClarificationQuestion> questions,
                                           String llmRawOutput, List<TestPointDraft> drafts) {}
}
