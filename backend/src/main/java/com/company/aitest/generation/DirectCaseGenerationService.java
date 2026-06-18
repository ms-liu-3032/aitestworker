package com.company.aitest.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectCaseGenerationService {
    private static final String SYSTEM_PROMPT = """
            你是资深测试工程师。请根据输入需求直接生成功能测试用例草稿。
            你必须只返回 JSON 数组，不要输出任何额外解释，不要输出 markdown。
            数组每个对象字段固定为：
            caseTitle, moduleName, precondition, steps, expectedResult, priority, sourceTestPoint, sourceBasis, unsupportedItems, confidence

            【要求】
            1. caseTitle：用例名称，简洁概括测试场景。
            2. moduleName：所属模块名。
            3. precondition：前置条件，写明执行用例前需要满足的状态（如"已登录且有权限"、"已有X条数据"），用分号分隔多个条件。
            4. steps：测试步骤，必须按 "1. xxx 2. xxx 3. xxx" 格式，每一步写清楚具体操作对象和动作（如"点击XX按钮"、"在XX输入框输入YY"、"查看XX列表"），不能笼统概括。
            5. expectedResult：预期结果，必须按 "1. xxx 2. xxx 3. xxx" 格式，与 steps 一一对应，每步写明页面应展示的具体状态、字段值或交互反馈（如"列表显示N条记录"、"弹出确认弹窗"、"状态变为已取消"）。
            6. priority：P0/P1/P2/P3/P4。
            7. sourceTestPoint：该用例对应的测试点标题，必须来自输入中的测试点或需求目标。
            8. sourceBasis：数组，写明来自需求、TOM、业务包、页面画像、轨迹摘要中的依据名称。
            9. unsupportedItems：数组，写明没有证据支撑但为了可执行性暂时假设的内容；没有则返回空数组。
            10. confidence：0~1 数字。证据不足或 unsupportedItems 非空时不得高于 0.65。

            steps 和 expectedResult 的条数必须一致，确保每一步都有对应预期。
            不允许生成与输入分析/测试点无关的业务场景；没有证据时宁可输出低置信草稿，也不要编造为事实。
            """;

    private static final String MINI_TOM_SYSTEM_PROMPT_EXTENSION = """

            【Mini-TOM 辅助规则】
            你已获得当前项目已确认的测试对象模型（Mini-TOM）上下文。
            请遵守以下规则：
            1. TOM 上下文中列出的模块、页面、字段、角色、流程、状态是已确认的测试对象，请优先围绕它们设计用例。
            2. 仅使用 status=ACTIVE 的 TOM；忽略 CANDIDATE 或 REJECTED 的对象。
            3. 如果用户需求与 TOM 上下文存在冲突，以用户需求为准，但在用例的 assumptionNote 中说明冲突点。
            4. 如果 TOM 上下文中匹配到的模块/页面/字段在用户需求中未提及，仍然可以生成用例，但标记 isAssumption=1。
            5. 请利用 suggestedAssertions 中的断言建议来设计预期结果。
            6. 如果用户需求涉及的范围超出 TOM 上下文覆盖范围，请正常生成用例，不受 TOM 限制。
            """;

    private final GenerationTaskService generationTaskService;
    private final LlmGateway llmGateway;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final MiniTomService miniTomService;
    private final ClarificationService clarificationService;

    public DirectCaseGenerationService(GenerationTaskService generationTaskService, LlmGateway llmGateway,
                                       JdbcTemplate jdbcTemplate, JdbcClient jdbc, TimeProvider timeProvider,
                                       MiniTomService miniTomService, ClarificationService clarificationService) {
        this.generationTaskService = generationTaskService;
        this.llmGateway = llmGateway;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.timeProvider = timeProvider;
        this.objectMapper = new ObjectMapper();
        this.miniTomService = miniTomService;
        this.clarificationService = clarificationService;
    }

    public GenerateResult generateFromTask(Long projectId, Long taskId, CurrentUser user) {
        GenerationTaskRecord task = generationTaskService.get(projectId, taskId);
        if (task.modelConfigId() == null) {
            throw new BusinessException("任务未配置模型，无法生成用例");
        }
        if (user == null || user.id() == null) {
            throw new BusinessException("缺少调用用户上下文");
        }

        // 检查是否有未回答的反问
        if (clarificationService.hasPendingQuestions(taskId)) {
            var questions = clarificationService.listQuestions(taskId).stream()
                    .filter(q -> "PENDING".equals(q.answerStatus())).toList();
            return new GenerateResult(taskId, null, List.of(), 0, true, questions, List.of());
        }

        String systemPrompt;
        String userPrompt;
        int tomHitCount = 0;

        if (Boolean.TRUE.equals(task.useMiniTom())) {
            // --- Mini-TOM 辅助生成路径（Project + System 融合） ---
            MiniTomService.TestScopeResult scope = miniTomService.buildTestScope(
                    projectId, task.requirementText(), user);

            String tomContext = buildTomContextString(scope);
            tomHitCount = countTomHits(scope);

            systemPrompt = SYSTEM_PROMPT + MINI_TOM_SYSTEM_PROMPT_EXTENSION;
            userPrompt = """
                    【需求描述】
                    %s

                    %s

                    【补充提示词】
                    %s
                    """.formatted(
                            emptySafe(task.requirementText()),
                            tomContext,
                            emptySafe(task.promptSnapshot()));

            // 持久化 TOM 快照（单独事务）
            persistTomSnapshots(taskId, scope, task);
        } else {
            systemPrompt = SYSTEM_PROMPT;
            userPrompt = """
                    【需求描述】
                    %s

                    【补充提示词】
                    %s
                    """.formatted(emptySafe(task.requirementText()), emptySafe(task.promptSnapshot()));
        }

        // LLM 调用（不在事务中）
        LlmInvocationResponse response = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, task.id(),
                "GENERATION", LlmStage.TEST_CASE_GEN,
                task.modelConfigId(), null, null, Map.of(
                        "requirementText", emptySafe(task.requirementText()),
                        "promptSnapshot", emptySafe(task.promptSnapshot()),
                        "useMiniTom", Boolean.TRUE.equals(task.useMiniTom()),
                        "tomHitCount", tomHitCount),
                systemPrompt, userPrompt, null, 16384));

        if (response.status() != LlmInvocationStatus.OK) {
            throw new BusinessException("生成失败：" + emptySafe(response.errorMessage()));
        }
        String output = response.content();

        // 解析 + 持久化草稿（单独事务）
        List<TestCaseDraftView> saved = saveDrafts(projectId, taskId, output, user);

        // 收集假设
        List<String> assumptions = List.of();
        if (task.assumptionsSnapshot() != null && !task.assumptionsSnapshot().isBlank()) {
            try {
                assumptions = objectMapper.readValue(task.assumptionsSnapshot(), new TypeReference<>() {});
            } catch (Exception ignored) {}
        }

        return new GenerateResult(task.id(), output, saved, tomHitCount, false, List.of(), assumptions);
    }

    @Transactional
    protected void persistTomSnapshots(Long taskId, MiniTomService.TestScopeResult scope, GenerationTaskRecord task) {
        String tomContextSnapshotJson = null;
        String testScopeSnapshotJson = null;
        String projectTomSnapshot = null;
        String systemTomSnapshot = null;
        try {
            tomContextSnapshotJson = objectMapper.writeValueAsString(scope);
            testScopeSnapshotJson = objectMapper.writeValueAsString(scope);
            var projectToms = scope.tomEvidence.stream().filter(e -> "PROJECT".equals(e.scope())).toList();
            var systemToms = scope.tomEvidence.stream().filter(e -> "SYSTEM".equals(e.scope())).toList();
            projectTomSnapshot = objectMapper.writeValueAsString(projectToms);
            systemTomSnapshot = objectMapper.writeValueAsString(systemToms);
        } catch (JsonProcessingException ignored) {}

        jdbcTemplate.update("""
                UPDATE generation_task SET
                    mini_tom_context_snapshot = ?, test_scope_snapshot = ?, tom_hit_count = ?,
                    project_tom_snapshot = ?, system_tom_snapshot = ?,
                    clarification_questions_snapshot = ?, clarification_answers_snapshot = ?,
                    assumptions_snapshot = ?, updated_at = ?
                WHERE id = ?
                """, tomContextSnapshotJson, testScopeSnapshotJson,
                countTomHits(scope), projectTomSnapshot, systemTomSnapshot,
                null, null, task.assumptionsSnapshot(),
                timeProvider.now(), taskId);
    }

    @Transactional
    protected List<TestCaseDraftView> saveDrafts(Long projectId, Long taskId, String llmOutput, CurrentUser user) {
        List<CaseDraftInput> parsed = parseOutput(llmOutput, taskId);
        LocalDateTime now = timeProvider.now();
        List<TestCaseDraftView> saved = new ArrayList<>();
        int i = 1;
        for (CaseDraftInput item : parsed) {
            String caseNo = "TC-" + taskId + "-" + i++;
            jdbcTemplate.update("""
                    insert into test_case_draft(task_id, project_id, test_point_id, case_no, case_title, project_name,
                      module_name, precondition, steps, expected_result, priority, case_type, design_method,
                      source_refs_json, is_assumption, assumption_note, compliance_mark, user_feedback, quality_status,
                      version_no, asset_status, case_scope, case_status, created_by, created_at, updated_at)
                    values (?, ?, null, ?, ?, null, ?, ?, ?, ?, ?, 'FUNCTIONAL', '场景法', ?, 0, null, 'UNMARKED',
                      null, ?, 1, 'DRAFT', 'PERSONAL', 'DRAFT', ?, ?, ?)
                    """, taskId, projectId, caseNo, item.caseTitle(), item.moduleName(), item.precondition(),
                    item.steps(), item.expectedResult(), normalizePriority(item.priority()),
                    buildSourceRefsJson(taskId, item), draftQualityStatus(item), user.id(), now, now);
            Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
            saved.add(getDraftById(id));
        }
        return saved;
    }

    public List<TestCaseDraftView> listDrafts(Long projectId, Long taskId) {
        generationTaskService.get(projectId, taskId);
        return jdbc.sql("""
                select id, task_id, case_no, case_title, module_name, precondition, steps, expected_result, priority, created_at
                from test_case_draft
                where project_id = :projectId and task_id = :taskId
                order by id asc
                """)
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query(this::mapDraft)
                .list();
    }

    private TestCaseDraftView getDraftById(Long id) {
        return jdbc.sql("""
                select id, task_id, case_no, case_title, module_name, precondition, steps, expected_result, priority, created_at
                from test_case_draft where id = :id
                """)
                .param("id", id)
                .query(this::mapDraft)
                .single();
    }

    private TestCaseDraftView mapDraft(ResultSet rs, int rowNum) throws SQLException {
        return new TestCaseDraftView(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getString("case_no"),
                rs.getString("case_title"),
                rs.getString("module_name"),
                rs.getString("precondition"),
                rs.getString("steps"),
                rs.getString("expected_result"),
                rs.getString("priority"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    // =====================================================================
    // Mini-TOM 上下文构建
    // =====================================================================

    private String buildTomContextString(MiniTomService.TestScopeResult scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前项目已确认 Mini-TOM 测试范围】\n\n");

        appendTomSection(sb, "影响模块", scope.affectedModules);
        appendTomSection(sb, "影响页面", scope.affectedPages);
        appendTomSection(sb, "影响字段", scope.affectedFields);
        appendTomSection(sb, "影响角色", scope.affectedRoles);
        appendTomSection(sb, "影响流程", scope.affectedFlows);
        appendTomSection(sb, "影响状态", scope.affectedStates);
        appendTomSection(sb, "建议断言", scope.suggestedAssertions);

        if (scope.suggestedQuestions != null && !scope.suggestedQuestions.isEmpty()) {
            sb.append("\n【建议反问】\n");
            for (String q : scope.suggestedQuestions) {
                sb.append("- ").append(q).append("\n");
            }
        }

        if (scope.unsuggestedExtensions != null && !scope.unsuggestedExtensions.isEmpty()) {
            sb.append("\n【不建议扩展范围】\n");
            for (String e : scope.unsuggestedExtensions) {
                sb.append("- ").append(e).append("\n");
            }
        }

        return sb.toString();
    }

    private void appendTomSection(StringBuilder sb, String title, List<TestObjectModelRecord> toms) {
        if (toms == null || toms.isEmpty()) return;
        sb.append("### ").append(title).append("\n");
        for (TestObjectModelRecord tom : toms) {
            sb.append("- ").append(tom.name());
            if (tom.description() != null && !tom.description().isBlank()) {
                sb.append("：").append(tom.description());
            }
            sb.append("\n");
        }
    }

    private int countTomHits(MiniTomService.TestScopeResult scope) {
        int count = 0;
        if (scope.affectedModules != null) count += scope.affectedModules.size();
        if (scope.affectedPages != null) count += scope.affectedPages.size();
        if (scope.affectedFields != null) count += scope.affectedFields.size();
        if (scope.affectedRoles != null) count += scope.affectedRoles.size();
        if (scope.affectedFlows != null) count += scope.affectedFlows.size();
        if (scope.affectedStates != null) count += scope.affectedStates.size();
        if (scope.suggestedAssertions != null) count += scope.suggestedAssertions.size();
        return count;
    }

    // =====================================================================
    // 输出解析
    // =====================================================================

    private List<CaseDraftInput> parseOutput(String rawOutput, Long taskId) {
        String jsonText = extractJson(rawOutput);
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(jsonText, new TypeReference<>() {
            });
            List<CaseDraftInput> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(new CaseDraftInput(
                        str(row.get("caseTitle"), "用例-" + taskId),
                        str(row.get("moduleName"), "默认模块"),
                        str(row.get("precondition"), ""),
                        str(row.get("steps"), ""),
                        str(row.get("expectedResult"), ""),
                        str(row.get("priority"), "P2"),
                        str(row.get("sourceTestPoint"), ""),
                        listValue(row.get("sourceBasis")),
                        listValue(row.get("unsupportedItems")),
                        confidenceValue(row.get("confidence"))
                ));
            }
            if (result.isEmpty()) {
                throw new BusinessException("模型未返回可用用例");
            }
            return result;
        } catch (JsonProcessingException ex) {
            return List.of(new CaseDraftInput(
                    "模型返回原文（待人工整理）",
                    "默认模块",
                    "",
                    rawOutput == null ? "" : rawOutput,
                    "请人工确认并整理为标准测试用例",
                    "P2",
                    "",
                    List.of("模型原始输出"),
                    List.of("模型输出未能解析为标准 JSON"),
                    0.2
            ));
        }
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

    private List<String> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = str(value, "");
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(text);
    }

    private double confidenceValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0.0, Math.min(1.0, number.doubleValue()));
        }
        try {
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(String.valueOf(value))));
        } catch (Exception ignored) {
            return 0.5;
        }
    }

    private String draftQualityStatus(CaseDraftInput item) {
        if (item == null) {
            return "LOW_EVIDENCE";
        }
        if (item.sourceBasis().isEmpty() || !item.unsupportedItems().isEmpty() || item.confidence() < 0.65
                || item.steps().isBlank() || item.expectedResult().isBlank()
                || countActionLines(item.steps()) != countActionLines(item.expectedResult())
                || item.caseTitle().contains("模型返回原文") || item.moduleName().contains("默认模块")) {
            return "LOW_EVIDENCE";
        }
        if (item.sourceTestPoint().isBlank() || item.confidence() < 0.8) {
            return "PARTIAL";
        }
        return "PASS";
    }

    private String emptySafe(String value) {
        return value == null ? "" : value;
    }

    private String buildSourceRefsJson(Long taskId, CaseDraftInput item) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "source", "LLM_DIRECT",
                    "taskId", taskId,
                    "sourceTestPoint", item.sourceTestPoint(),
                    "sourceBasis", item.sourceBasis(),
                    "unsupportedItems", item.unsupportedItems(),
                    "confidence", item.confidence(),
                    "stepCount", countActionLines(item.steps()),
                    "expectedCount", countActionLines(item.expectedResult())
            ));
        } catch (Exception ignored) {
            return "{\"source\":\"LLM_DIRECT\",\"taskId\":" + taskId + "}";
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
        return (int) java.util.Arrays.stream(text.split("\\R+"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .count();
    }

    record CaseDraftInput(String caseTitle, String moduleName, String precondition, String steps,
                          String expectedResult, String priority, String sourceTestPoint,
                          List<String> sourceBasis, List<String> unsupportedItems, double confidence) {
    }

    public record TestCaseDraftView(Long id, Long taskId, String caseNo, String caseTitle, String moduleName,
                                    String precondition, String steps, String expectedResult, String priority,
                                    LocalDateTime createdAt) {
    }

    public record GenerateResult(Long taskId, String llmRawOutput, List<TestCaseDraftView> drafts, int tomHitCount,
                                  boolean needClarification, List<ClarificationService.ClarificationQuestion> questions,
                                  List<String> assumptions) {
    }
}
