package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class RequirementAnalysisServiceTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractJsonReadsStructuredFieldsFromValidRootJson() {
        String llmOutput = """
                {
                  "analysis": {
                    "requirement_understanding": "u"
                  },
                  "test_points": [
                    { "title": "t1" }
                  ]
                }
                """;

        assertEquals("{\"requirement_understanding\":\"u\"}",
                RequirementAnalysisService.extractJson(llmOutput, "analysis"));
        assertEquals("[{\"title\":\"t1\"}]",
                RequirementAnalysisService.extractJson(llmOutput, "test_points"));
    }

    @Test
    void extractJsonReadsFromMarkdownFence() {
        String llmOutput = """
                下面是分析结果：
                ```json
                {
                  "analysis": {
                    "business_domain": "crm"
                  }
                }
                ```
                """;

        assertEquals("{\"business_domain\":\"crm\"}",
                RequirementAnalysisService.extractJson(llmOutput, "analysis"));
    }

    @Test
    void extractJsonReturnsNullForUnbalancedFragmentInsteadOfThrowing() {
        String llmOutput = """
                {
                  "analysis": {
                    "requirement_understanding": "abc",
                    "uncertain_items": ["x", "y"]
                """;

        assertNull(RequirementAnalysisService.extractJson(llmOutput, "analysis"));
    }

    @Test
    void extractJsonReturnsNullForMalformedJsonValueInsteadOfPassingThrough() {
        String llmOutput = """
                {
                  "analysis": { invalid json },
                  "test_points": []
                }
                """;

        assertNull(RequirementAnalysisService.extractJson(llmOutput, "analysis"));
        assertEquals("[]", RequirementAnalysisService.extractJson(llmOutput, "test_points"));
    }

    @Test
    void normalizeJsonColumnReturnsCanonicalJsonForValidInput() {
        assertEquals("[{\"question\":\"q1\"}]",
                RequirementAnalysisService.normalizeJsonColumn("""
                        [
                          { "question": "q1" }
                        ]
                        """));
    }

    @Test
    void normalizeJsonColumnReturnsNullForInvalidInput() {
        assertNull(RequirementAnalysisService.normalizeJsonColumn("[{\"question\":\"q1\",}]"));
        assertNull(RequirementAnalysisService.normalizeJsonColumn("not-json"));
    }

    @Test
    void generationRequirementCarriesAnalysisAndTestPoints() {
        var now = java.time.LocalDateTime.of(2026, 6, 15, 10, 0);
        var analysis = new RequirementAnalysisRecord(
                9L,
                3L,
                2,
                0,
                "用户提交报销单后，财务需要审批。",
                "{\"requirement_understanding\":\"报销审批流\"}",
                null,
                "[]",
                "[{\"index\":0,\"answer\":\"审批节点为直属主管和财务\"}]",
                "[{\"assumption\":\"金额阈值按默认规则处理\"}]",
                "[{\"title\":\"提交报销单后进入主管审批\"}]",
                null,
                null,
                null,
                "CONFIRMED",
                now,
                now
        );

        String prompt = RequirementAnalysisService.buildGenerationRequirementText(analysis);

        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("用户提交报销单"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("报销审批流"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("提交报销单后进入主管审批"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("用例必须围绕上述需求分析和测试点展开"));
    }

    @Test
    void enrichAnalysisResultKeepsClarificationQuestionsSeparateFromUncertainItems() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichAnalysisResult",
                String.class,
                ProjectSemanticContextService.BuildResult.class,
                String.class
        );
        method.setAccessible(true);

        String output = (String) method.invoke(
                service,
                "{\"requirement_understanding\":\"禁止外部访问配置\"}",
                null,
                """
                [
                  {
                    "question": "管理员配置禁访规则的入口在哪里？",
                    "reason": "交互入口会影响用例步骤",
                    "impact": "无法确定应覆盖独立配置页还是用户管理页"
                  }
                ]
                """
        );

        var root = objectMapper.readTree(output);
        assertEquals(1, root.path("clarification_questions").size());
        assertTrue(root.path("uncertain_items").isArray());
        assertTrue(root.path("clarification_questions").toString().contains("管理员配置禁访规则的入口在哪里"));
        assertTrue(root.path("clarification_questions").toString().contains("交互入口会影响用例步骤"));
        org.junit.jupiter.api.Assertions.assertFalse(
                root.path("uncertain_items").toString().contains("管理员配置禁访规则的入口在哪里")
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void evidenceSummaryCountsSystemTomSignalsAsTomRefs() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "buildEvidenceSummary",
                ProjectSemanticContextService.BuildResult.class
        );
        method.setAccessible(true);

        var buildResult = new ProjectSemanticContextService.BuildResult("", List.of(
                new ProjectSemanticContextService.SemanticSignal(
                        "TOM:系统", "公共审批流", "类型：FLOW；层级：SYSTEM", null, 1.2, LocalDateTime.now()),
                new ProjectSemanticContextService.SemanticSignal(
                        "TOM:项目", "项目配置页", "类型：PAGE；层级：PROJECT", null, 1.2, LocalDateTime.now())
        ));

        Map<String, Object> summary = (Map<String, Object>) method.invoke(service, buildResult);

        List<String> tomRefs = (List<String>) summary.get("tom_node_refs");
        assertTrue(tomRefs.contains("公共审批流"));
        assertTrue(tomRefs.contains("项目配置页"));
    }

    @Test
    void supplementMissingCases_filtersDraftsByVersionNotSession() throws Exception {
        // 构造 mock JdbcClient
        JdbcClient mockJdbc = mock(JdbcClient.class);
        var statementSpec = mock(JdbcClient.StatementSpec.class);
        var mappedSpec = mock(JdbcClient.MappedQuerySpec.class);
        when(mockJdbc.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(mappedSpec);
        when(mappedSpec.list()).thenReturn(List.of());

        var service = new RequirementAnalysisService(
                mockJdbc, null, null, null, null, null, null, null, null, null, null, null);

        // 当前分析 version=3，testPoints 含 "测试点A"
        var analysis = new RequirementAnalysisRecord(
                100L, 10L, 3, 0,
                "需求文本",
                "{\"requirement_understanding\":\"测试\"}",
                null, null, null, null,
                "[{\"title\":\"测试点A\"},{\"title\":\"测试点B\"}]",
                null, null, null,
                "NEED_CONFIRMATION",
                LocalDateTime.now(), LocalDateTime.now()
        );

        var user = new CurrentUser(1L, "test", null);

        // 调用 supplementMissingCases
        var method = RequirementAnalysisService.class.getDeclaredMethod(
                "supplementMissingCases", Long.class, RequirementAnalysisRecord.class, CurrentUser.class);
        method.setAccessible(true);
        method.invoke(service, 10L, analysis, user);

        // 验证 JdbcClient.sql() 被调用
        org.mockito.Mockito.verify(mockJdbc).sql(anyString());
        // 验证 SQL 包含 analysis_version（通过检查 param 调用的参数名）
        org.mockito.Mockito.verify(statementSpec).param("aver", 3);
    }

    @Test
    void incrementalAnalyze_semanticInputContainsRequirementAndSupplement() throws Exception {
        // 验证增量分析的 semantic 检索输入包含：
        // 1. 原始 requirementText
        // 2. supplementContent（用户补充）
        // 3. clarificationAnswers（已有澄清答案）

        var user = new CurrentUser(1L, "test", null);

        // 模拟 semanticContextService.build() 捕获其参数
        ProjectSemanticContextService mockSemantic = mock(ProjectSemanticContextService.class);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(mockSemantic.build(any(Long.class), captor.capture(), any(List.class), any(int.class)))
                .thenReturn(new ProjectSemanticContextService.BuildResult("evidence", List.of()));

        // 构造 mock JDBC 返回上一版分析记录
        JdbcClient mockJdbc = mock(JdbcClient.class);
        var statementSpec = mock(JdbcClient.StatementSpec.class);
        var mappedSpec = mock(JdbcClient.MappedQuerySpec.class);
        when(mockJdbc.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(mappedSpec);

        // mock getLatestAnalysis 返回的记录
        RequirementAnalysisRecord existingRecord = new RequirementAnalysisRecord(
                1L, 10L, 2, 0,
                "原始需求文本",
                "{\"requirement_understanding\":\"test\"}",
                null, null,
                "[{\"index\":0,\"answer\":\"澄清答案内容\"}]",
                null, null, null, null, null,
                "NEED_CONFIRMATION",
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(mappedSpec.list()).thenReturn(List.of(existingRecord));

        var mockSession = mock(GenerationSessionService.class);
        var session = mock(com.company.aitest.generation.session.GenerationSessionRecord.class);
        when(session.projectId()).thenReturn(1L);
        when(session.executionTaskId()).thenReturn(1L);
        when(session.modelConfigId()).thenReturn(1L);
        when(session.useMiniTom()).thenReturn(false);
        when(session.promptSnapshot()).thenReturn(null);
        when(session.sessionTitle()).thenReturn("test");
        when(mockSession.get(null, 10L, user)).thenReturn(session);

        // mock LlmGateway — 抛出异常使测试提前退出，但 semantic input 已捕获
        com.company.aitest.llm.gateway.LlmGateway mockLlm = mock(com.company.aitest.llm.gateway.LlmGateway.class);
        when(mockLlm.invoke(any(com.company.aitest.llm.gateway.LlmInvocationRequest.class)))
                .thenThrow(new com.company.aitest.common.BusinessException("mock LLM"));

        var fullService = new RequirementAnalysisService(
                mockJdbc, null, null, mockLlm, mockSession, null, null, null, null, null, mockSemantic, null);

        try {
            fullService.incrementalAnalyze(10L, "补充内容", user);
        } catch (Exception ignored) {
            // LLM mock 抛出 BusinessException，但 semantic input 已在 LLM 调用前捕获
        }

        // 验证 buildSemanticEvidence 被调用且参数包含三个部分
        org.mockito.Mockito.verify(mockSemantic).build(any(Long.class), anyString(), any(List.class), any(int.class));
        String capturedSemanticInput = captor.getValue();
        assertTrue(capturedSemanticInput.contains("原始需求文本"), "应包含原始 requirementText");
        assertTrue(capturedSemanticInput.contains("补充内容"), "应包含 supplementContent");
        assertTrue(capturedSemanticInput.contains("澄清答案内容"), "应包含 clarificationAnswers");
    }

    @Test
    void enrichAnalysisResultContainsReviewRiskQuestionsAndRiskFields() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichAnalysisResult", String.class,
                ProjectSemanticContextService.BuildResult.class, String.class);
        method.setAccessible(true);

        String input = """
                {
                  "analysis": {
                    "requirement_type": "RULE",
                    "requirement_understanding": "审批规则配置",
                    "review_risk_questions": [
                      {"question": "审批节点数量是否有限制？", "reason": "影响用例拆分", "impact": "需确认上限"}
                    ],
                    "risk_scenarios": ["并发审批冲突"],
                    "boundary_conditions": ["最大审批节点数为10"]
                  },
                  "test_points": [{"title": "t1"}]
                }
                """;

        String output = (String) method.invoke(service, input, null, null);
        var root = objectMapper.readTree(output);

        // 验证新字段存在（enrichAnalysisResult 在根级别兜底补全）
        assertTrue(root.has("requirement_type"));
        assertEquals("RULE", root.get("requirement_type").asText());
        assertTrue(root.has("review_risk_questions"));
        assertTrue(root.get("review_risk_questions").isArray());
        assertTrue(root.has("risk_scenarios"));
        assertTrue(root.has("boundary_conditions"));

        // 验证 clarification_questions 与 review_risk_questions 不混用
        assertFalse(root.path("uncertain_items").toString().contains("审批节点数量"));
    }

    @Test
    void enrichAnalysisResultProvidesDefaultsForNewFieldsWhenMissing() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichAnalysisResult", String.class,
                ProjectSemanticContextService.BuildResult.class, String.class);
        method.setAccessible(true);

        String input = "{\"analysis\": {\"requirement_understanding\": \"test\"}}";
        String output = (String) method.invoke(service, input, null, null);
        var root = objectMapper.readTree(output);

        // 验证兜底默认值
        assertEquals("MIXED", root.path("requirement_type").asText());
        assertTrue(root.path("review_risk_questions").isArray());
        assertEquals(0, root.path("review_risk_questions").size());
        assertTrue(root.path("risk_scenarios").isArray());
        assertEquals(0, root.path("risk_scenarios").size());
        assertTrue(root.path("boundary_conditions").isArray());
        assertEquals(0, root.path("boundary_conditions").size());
    }

    @Test
    void enrichTestPointsSetsDefaultPointTypeAndPriorityHint() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichTestPoints", String.class,
                ProjectSemanticContextService.BuildResult.class);
        method.setAccessible(true);

        String input = "[{\"title\":\"t1\",\"description\":\"desc\"}]";
        String output = (String) method.invoke(service, input, null);
        var root = objectMapper.readTree(output);

        assertTrue(root.isArray());
        var tp = root.get(0);
        assertEquals("MAIN_FLOW", tp.get("point_type").asText());
        assertEquals("CORE", tp.get("priority_hint").asText());
    }

    @Test
    void buildAnalysisSystemPromptAcceptsRequirementType() {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        String prompt = service.buildAnalysisSystemPrompt();
        assertTrue(prompt.contains("requirement_type"));
        assertTrue(prompt.contains("RULE"));
        assertTrue(prompt.contains("FORM"));
        assertTrue(prompt.contains("UI"));
        assertTrue(prompt.contains("STATE"));
        assertTrue(prompt.contains("DATA"));
        assertTrue(prompt.contains("MIXED"));
        assertTrue(prompt.contains("review_risk_questions"));
        assertTrue(prompt.contains("risk_scenarios"));
        assertTrue(prompt.contains("boundary_conditions"));
        assertTrue(prompt.contains("point_type"));
        assertTrue(prompt.contains("priority_hint"));
    }

    // === P2: 拆点质量补强回归测试 ===

    @Test
    void enrichTestPointsPreservesExistingPointTypeAndPriorityHint() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichTestPoints", String.class,
                ProjectSemanticContextService.BuildResult.class);
        method.setAccessible(true);

        String input = "[{\"title\":\"t1\",\"point_type\":\"BOUNDARY\",\"priority_hint\":\"RISK\",\"description\":\"desc\"}]";
        String output = (String) method.invoke(service, input, null);
        var root = objectMapper.readTree(output);

        assertTrue(root.isArray());
        var tp = root.get(0);
        assertEquals("BOUNDARY", tp.get("point_type").asText());
        assertEquals("RISK", tp.get("priority_hint").asText());
    }

    @Test
    void buildAnalysisSystemPromptContainsTypeRoutingRules() {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        String prompt = service.buildAnalysisSystemPrompt();
        // 验证 prompt 包含按需求类型选拆解骨架的规则
        assertTrue(prompt.contains("RULE 型重点覆盖条件组合"));
        assertTrue(prompt.contains("FORM 型重点覆盖字段校验"));
        assertTrue(prompt.contains("UI 型重点覆盖交互流程"));
        assertTrue(prompt.contains("STATE 型重点覆盖状态流转"));
        assertTrue(prompt.contains("DATA 型重点覆盖一致性"));
        assertTrue(prompt.contains("MIXED 型按涉及的主要类型组合拆解"));
        // 验证原型/页面控件不等于测试范围的规则
        assertTrue(prompt.contains("原型/页面上已有控件，不等于本次新增测试范围"));
        // 验证证据伪装规则
        assertTrue(prompt.contains("不允许伪装成已确认事实"));
    }

    // === P3: 输入源与证据规则补强测试 ===

    @Test
    void buildAnalysisSystemPromptContainsInputSourceRules() {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        String prompt = service.buildAnalysisSystemPrompt();
        // 验证输入源识别相关规则
        assertTrue(prompt.contains("项目证据上下文"));
        assertTrue(prompt.contains("TOM、页面画像、业务包、轨迹摘要"));
        assertTrue(prompt.contains("LOW_EVIDENCE"));
        assertTrue(prompt.contains("needs_confirmation"));
    }

    @Test
    void enrichAnalysisResultSeparatesEvidenceInsufficiencyFromRequirementNotDescribed() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichAnalysisResult", String.class,
                ProjectSemanticContextService.BuildResult.class, String.class);
        method.setAccessible(true);

        // 没有语义信号时，uncertain_items 应明确说明"未命中证据"
        String input = "{\"analysis\": {\"requirement_understanding\": \"test\"}}";
        String output = (String) method.invoke(service, input, null, null);
        var root = objectMapper.readTree(output);

        assertTrue(root.get("uncertain_items").isArray());
        var items = root.get("uncertain_items");
        assertTrue(items.size() > 0);
        String itemText = items.get(0).asText();
        // 验证是"证据不足"而非"需求未描述"
        assertTrue(itemText.contains("未命中") || itemText.contains("证据"),
                "应明确是证据不足而非需求未描述");
    }

    @Test
    void enrichTestPointsPreservesExistingSourceBasisAndSourceRefs() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichTestPoints", String.class,
                ProjectSemanticContextService.BuildResult.class);
        method.setAccessible(true);

        String input = """
                [{"title":"t1","source_basis":["TOM:审批流"],"source_refs":{"tom_node_refs":["审批流"],"page_refs":[],"business_pack_refs":[],"trace_refs":[]}}]
                """;
        String output = (String) method.invoke(service, input, null);
        var root = objectMapper.readTree(output);

        assertTrue(root.isArray());
        var tp = root.get(0);
        // 验证保留原始 source_basis
        assertTrue(tp.get("source_basis").isArray());
        assertTrue(tp.get("source_basis").toString().contains("TOM:审批流"));
        // 验证保留原始 source_refs
        assertTrue(tp.get("source_refs").has("tom_node_refs"));
    }

    // === P3 输入源识别测试 ===

    @Test
    void enrichAnalysisResultProvidesDefaultInputSourcesWhenMissing() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichAnalysisResult", String.class,
                ProjectSemanticContextService.BuildResult.class, String.class);
        method.setAccessible(true);

        String input = "{\"analysis\": {\"requirement_understanding\": \"test\"}}";
        String output = (String) method.invoke(service, input, null, null);
        var root = objectMapper.readTree(output);

        // 验证 input_sources 兜底默认值
        assertTrue(root.has("input_sources"));
        assertTrue(root.get("input_sources").isArray());
        assertTrue(root.get("input_sources").size() > 0);
        assertEquals("UNKNOWN", root.get("input_sources").get(0).asText());
    }

    @Test
    void enrichAnalysisResultPreservesInputSourcesFromAnalysis() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichAnalysisResult", String.class,
                ProjectSemanticContextService.BuildResult.class, String.class);
        method.setAccessible(true);

        String input = """
                {
                  "analysis": {
                    "requirement_understanding": "test",
                    "input_sources": ["PRD_TEXT", "BLUEPRINT"]
                  }
                }
                """;
        String output = (String) method.invoke(service, input, null, null);
        var root = objectMapper.readTree(output);

        assertTrue(root.has("input_sources"));
        assertTrue(root.get("input_sources").isArray());
        assertEquals(2, root.get("input_sources").size());
        assertEquals("PRD_TEXT", root.get("input_sources").get(0).asText());
        assertEquals("BLUEPRINT", root.get("input_sources").get(1).asText());
    }

    @Test
    void buildAnalysisSystemPromptContainsInputSourceAndBlueprintRules() {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        String prompt = service.buildAnalysisSystemPrompt();

        // 验证 input_sources 字段在 schema 中
        assertTrue(prompt.contains("input_sources"));
        assertTrue(prompt.contains("input_source_notes"));
        // 验证输入源枚举值
        assertTrue(prompt.contains("PRD_TEXT"));
        assertTrue(prompt.contains("PRD_FILE"));
        assertTrue(prompt.contains("BLUEPRINT"));
        assertTrue(prompt.contains("PROTO_OR_DESIGN"));
        assertTrue(prompt.contains("TOM"));
        assertTrue(prompt.contains("VERBAL"));
        assertTrue(prompt.contains("UNKNOWN"));
        // 验证蓝湖/原型硬规则的结构化触发
        assertTrue(prompt.contains("BLUEPRINT 或 PROTO_OR_DESIGN"));
        assertTrue(prompt.contains("原型/设计稿控件存在不等于本次新增测试范围"));
        // 验证 review_risk_questions 的 source_basis 约束
        assertTrue(prompt.contains("review_risk_questions 中的问题必须标注 source_basis"));
    }
}
