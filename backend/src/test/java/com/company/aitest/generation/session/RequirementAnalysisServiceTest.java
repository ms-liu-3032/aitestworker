package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
                null, null, null, null, null, null, null, null, null, null, null
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
                "{\"requirement_understanding\":\"禁止用户访问配置\"}",
                null,
                """
                [
                  {
                    "question": "管理员配置访问控制规则的入口在哪里？",
                    "reason": "交互入口会影响用例步骤",
                    "impact": "无法确定应覆盖独立配置页还是用户管理页"
                  }
                ]
                """
        );

        var root = objectMapper.readTree(output);
        assertEquals(1, root.path("clarification_questions").size());
        assertTrue(root.path("uncertain_items").isArray());
        assertTrue(root.path("clarification_questions").toString().contains("管理员配置访问控制规则的入口在哪里"));
        assertTrue(root.path("clarification_questions").toString().contains("交互入口会影响用例步骤"));
        org.junit.jupiter.api.Assertions.assertFalse(
                root.path("uncertain_items").toString().contains("管理员配置访问控制规则的入口在哪里")
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void evidenceSummaryCountsSystemTomSignalsAsTomRefs() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null
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
}
