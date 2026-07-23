package com.company.aitest.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DirectCaseGenerationServiceTest {

    @Test
    void extractsStructuredTestPointsAndRemovesThemFromBatchContext() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method extract = DirectCaseGenerationService.class.getDeclaredMethod("extractTestPointsFromRequirement", String.class);
        extract.setAccessible(true);
        Method strip = DirectCaseGenerationService.class.getDeclaredMethod("removeTestPointSection", String.class);
        strip.setAccessible(true);
        String requirement = """
                ## 原始需求
                申请人预约需要审批。

                ## 测试点
                [{"title":"预约提交","requirement_refs":["R1"]},{"title":"审批处理","requirement_refs":["R2"]}]

                ## TOM/项目证据快照
                预约状态：待审批→已通过
                """;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) extract.invoke(service, requirement);
        String context = (String) strip.invoke(service, requirement);

        assertEquals(2, points.size());
        assertEquals("预约提交", points.get(0).get("title"));
        assertFalse(context.contains("预约提交"));
        assertTrue(context.contains("预约状态：待审批→已通过"));
    }

    @Test
    void extractsCasePlanAsIndependentGenerationNodes() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method extract = DirectCaseGenerationService.class.getDeclaredMethod("extractCasePlanFromRequirement", String.class);
        extract.setAccessible(true);
        String requirement = """
                ## 测试点
                [{"id":"TP1","title":"提交预约"},{"id":"TP2","title":"审批预约"}]

                ## 用例编排计划
                [{"id":"CP1","case_strategy":"NODE_FOCUSED","source_test_point_refs":["TP2"],"precondition_test_point_refs":["TP1"],"case_designs":[{"id":"CD1","scenario":"审批通过"}]},
                 {"id":"CP2","case_strategy":"FLOW_COMPOSED","source_test_point_refs":["TP1","TP2"],"case_designs":[{"id":"CD2","scenario":"完整流程"}]}]
                """;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) extract.invoke(service, requirement);

        assertEquals(2, plans.size());
        assertEquals("CP1", plans.get(0).get("id"));
        assertEquals("NODE_FOCUSED", plans.get(0).get("case_strategy"));
        assertEquals(List.of("TP1"), plans.get(0).get("precondition_test_point_refs"));
        assertEquals("CD1", ((Map<?, ?>) ((List<?>) plans.get(0).get("case_designs")).get(0)).get("id"));
    }

    @Test
    void turnsUpstreamTestPointsIntoReadablePreconditionMaterialWithoutExpandingCurrentCoverage() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method summarize = DirectCaseGenerationService.class.getDeclaredMethod(
                "summarizePreconditionPoints", List.class, Map.class);
        summarize.setAccessible(true);
        Map<String, Map<String, Object>> points = Map.of(
                "TP1", Map.of("id", "TP1", "title", "预约已提交", "description", "申请人已提交预约并进入待审批状态",
                        "requirement_refs", List.of("R1"), "source_basis", List.of("需求")),
                "TP2", Map.of("id", "TP2", "title", "审批处理", "description", "审批人处理待审批预约")
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> material = (List<Map<String, Object>>) summarize.invoke(service, List.of("TP1"), points);

        assertEquals(1, material.size());
        assertEquals("TP1", material.get(0).get("trace_ref"));
        assertEquals("预约已提交", material.get(0).get("title"));
        assertEquals("申请人已提交预约并进入待审批状态", material.get(0).get("description"));
        assertFalse(material.get(0).containsKey("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchesIndependentNodePlansButKeepsFlowPlansAsDedicatedGenerationNodes() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method partition = DirectCaseGenerationService.class.getDeclaredMethod("partitionCasePlans", List.class, java.util.Set.class);
        partition.setAccessible(true);
        List<Map<String, Object>> plans = List.of(
                Map.of("id", "CP1", "case_strategy", "NODE_FOCUSED"),
                Map.of("id", "CP2", "case_strategy", "NODE_FOCUSED"),
                Map.of("id", "CP3", "case_strategy", "NODE_FOCUSED"),
                Map.of("id", "CP4", "case_strategy", "NODE_FOCUSED"),
                Map.of("id", "CP5", "case_strategy", "FLOW_COMPOSED"),
                Map.of("id", "CP6", "case_strategy", "NODE_FOCUSED")
        );

        List<List<Map<String, Object>>> batches = (List<List<Map<String, Object>>>) partition.invoke(service, plans, java.util.Set.of("CP2"));

        assertEquals(List.of("CP1", "CP3", "CP4"), batches.get(0).stream().map(plan -> String.valueOf(plan.get("id"))).toList());
        assertEquals(List.of("CP5"), batches.get(1).stream().map(plan -> String.valueOf(plan.get("id"))).toList());
        assertEquals(List.of("CP6"), batches.get(2).stream().map(plan -> String.valueOf(plan.get("id"))).toList());
    }

    @Test
    void persistsPlanDesignAndMultipleTestPointReferencesForResumeAndTraceability() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method sourceRefs = DirectCaseGenerationService.class.getDeclaredMethod(
                "buildSourceRefsJson", Long.class, DirectCaseGenerationService.CaseDraftInput.class);
        sourceRefs.setAccessible(true);
        var input = new DirectCaseGenerationService.CaseDraftInput(
                "完整流程", "模块", "前置", "1. 操作", "1. 成功", "P1",
                "POSITIVE", List.of("场景法"), List.of("VALID_FLOW"),
                "CP2", "CD3", "提交", List.of("TP1", "TP2"), List.of("需求"), List.of(), 0.9);

        String json = (String) sourceRefs.invoke(service, 7L, input);

        assertTrue(json.contains("\"sourceCasePlan\":\"CP2\""));
        assertTrue(json.contains("\"sourceCaseDesign\":\"CD3\""));
        assertTrue(json.contains("\"scenarioType\":\"POSITIVE\""));
        assertTrue(json.contains("\"designMethods\":[\"场景法\"]"));
        assertTrue(json.contains("\"TP1\""));
        assertTrue(json.contains("\"TP2\""));
    }

    @SuppressWarnings("unchecked")
    @Test
    void resumeSkipsOnlyPlansWhoseTestPointsAndDesignsAreCompletelyPersisted() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method completed = DirectCaseGenerationService.class.getDeclaredMethod(
                "completedSourceCasePlansFromRefs", List.class, List.class);
        completed.setAccessible(true);
        List<Map<String, Object>> plans = List.of(
                Map.of("id", "CP1", "source_test_point_refs", List.of("TP1", "TP2"),
                        "case_designs", List.of(Map.of("id", "CD1"), Map.of("id", "CD2"))),
                Map.of("id", "CP2", "source_test_point_refs", List.of("TP3"),
                        "case_designs", List.of(Map.of("id", "CD3")))
        );
        List<String> refs = List.of(
                "{\"sourceCasePlan\":\"CP1\",\"sourceCaseDesign\":\"CD1\",\"sourceTestPointRefs\":[\"TP1\",\"TP2\"]}",
                "{\"sourceCasePlan\":\"CP1\",\"sourceCaseDesign\":\"CD2\",\"sourceTestPointRefs\":[\"TP2\"]}",
                "{\"sourceCasePlan\":\"CP2\",\"sourceCaseDesign\":\"CD3\",\"sourceTestPointRefs\":[]}"
        );

        java.util.Set<String> result = (java.util.Set<String>) completed.invoke(service, refs, plans);

        assertEquals(java.util.Set.of("CP1"), result);
    }

    @Test
    void parsesMultiTestPointFlowCaseWithoutForcingDuplicateFlowDrafts() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method parse = DirectCaseGenerationService.class.getDeclaredMethod("parseOutput", String.class, Long.class);
        parse.setAccessible(true);
        String output = """
                {"cases":[{"caseTitle":"提交到通知完整流程","moduleName":"流程","precondition":"已登录","steps":"1. 提交\\n2. 审批\\n3. 通知","expectedResult":"1. 已提交\\n2. 已审批\\n3. 已通知","priority":"P1","scenarioType":"POSITIVE","designMethods":["场景法"],"designCoverage":["VALID_FLOW"],"sourceCasePlan":"CP2","sourceCaseDesign":"CD2","sourceTestPoint":"提交","sourceTestPointRefs":["TP1","TP2","TP3"],"sourceBasis":["需求"],"unsupportedItems":[],"confidence":0.9}]}
                """;

        @SuppressWarnings("unchecked")
        List<DirectCaseGenerationService.CaseDraftInput> cases = (List<DirectCaseGenerationService.CaseDraftInput>) parse.invoke(service, output, 1L);

        assertEquals(List.of("TP1", "TP2", "TP3"), cases.get(0).sourceTestPointRefs());
        assertEquals("CD2", cases.get(0).sourceCaseDesign());
        assertEquals("POSITIVE", cases.get(0).scenarioType());
    }

    @Test
    void normalizesInlineNumberedStepsWithoutDiscardingAnyAction() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method normalize = DirectCaseGenerationService.class.getDeclaredMethod("normalizeNumberedActions", String.class);
        normalize.setAccessible(true);

        String normalized = (String) normalize.invoke(service, "1. 填写信息 2. 点击提交；3. 查看结果");

        assertEquals("1. 填写信息\n2. 点击提交\n3. 查看结果", normalized);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsRepresentativeBoundaryCaseWhenRequiredCoverageIsMissing() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method missing = DirectCaseGenerationService.class.getDeclaredMethod(
                "missingDesignObligations", List.class, List.class);
        missing.setAccessible(true);
        Map<String, Object> design = Map.of(
                "id", "CD1", "scenario_type", "BOUNDARY",
                "design_methods", List.of("等价类划分法", "边界值分析法"),
                "coverage_requirements", List.of("VALID_EQUIVALENCE_CLASS", "INVALID_EQUIVALENCE_CLASS", "AT_BOUNDARY"));
        var oneCase = new DirectCaseGenerationService.CaseDraftInput(
                "边界内有效值", "表单", "", "1. 输入", "1. 通过", "P1", "BOUNDARY",
                List.of("边界值分析法"), List.of("AT_BOUNDARY"), "CP1", "CD1", "字段边界",
                List.of("TP1"), List.of("需求"), List.of(), 0.9);

        List<String> result = (List<String>) missing.invoke(service, List.of(design), List.of(oneCase));

        assertTrue(result.contains("CD1/方法:等价类划分法"));
        assertTrue(result.contains("CD1/覆盖:INVALID_EQUIVALENCE_CLASS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsMultipleFunctionalCasesThatTogetherCompleteOneDesign() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method missing = DirectCaseGenerationService.class.getDeclaredMethod(
                "missingDesignObligations", List.class, List.class);
        missing.setAccessible(true);
        Map<String, Object> design = Map.of(
                "id", "CD1", "scenario_type", "BOUNDARY",
                "design_methods", List.of("等价类划分法", "边界值分析法"),
                "coverage_requirements", List.of("VALID_EQUIVALENCE_CLASS", "INVALID_EQUIVALENCE_CLASS", "AT_BOUNDARY"));
        var valid = new DirectCaseGenerationService.CaseDraftInput(
                "有效等价类", "表单", "", "1. 输入", "1. 通过", "P1", "BOUNDARY",
                List.of("等价类划分法"), List.of("VALID_EQUIVALENCE_CLASS"), "CP1", "CD1", "字段边界",
                List.of("TP1"), List.of("需求"), List.of(), 0.9);
        var invalidBoundary = new DirectCaseGenerationService.CaseDraftInput(
                "无效边界", "表单", "", "1. 输入", "1. 拦截", "P1", "BOUNDARY",
                List.of("等价类划分法", "边界值分析法"),
                List.of("INVALID_EQUIVALENCE_CLASS", "AT_BOUNDARY"), "CP1", "CD1", "字段边界",
                List.of("TP1"), List.of("需求"), List.of(), 0.9);

        List<String> result = (List<String>) missing.invoke(service, List.of(design), List.of(valid, invalidBoundary));

        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsCasesWhoseStepsCannotBeExecutedOrTraced() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method quality = DirectCaseGenerationService.class.getDeclaredMethod(
                "caseExecutionQualityProblems", List.class);
        quality.setAccessible(true);
        var invalid = new DirectCaseGenerationService.CaseDraftInput(
                "提交预约", "预约", "已完成 TP1", "1. 填写\n2. 提交", "1. 提交成功", "P1", "POSITIVE",
                List.of("场景法"), List.of("VALID_FLOW"), "CP1", "CD1", "预约提交",
                List.of(), List.of(), List.of(), 0.9);

        List<String> result = (List<String>) quality.invoke(service, List.of(invalid));

        assertTrue(result.stream().anyMatch(item -> item.contains("步骤与预期未一一对应")));
        assertTrue(result.stream().anyMatch(item -> item.contains("缺少测试点来源")));
        assertTrue(result.stream().anyMatch(item -> item.contains("缺少需求或资产依据")));
        assertTrue(result.stream().anyMatch(item -> item.contains("内部编排编号")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsTraceableCaseWithOneExpectedResultPerStep() throws Exception {
        var service = new DirectCaseGenerationService(null, null, null, null, null, null, null, null);
        Method quality = DirectCaseGenerationService.class.getDeclaredMethod(
                "caseExecutionQualityProblems", List.class);
        quality.setAccessible(true);
        var valid = new DirectCaseGenerationService.CaseDraftInput(
                "提交预约", "预约", "用户已登录", "1. 填写预约信息\n2. 点击提交", "1. 表单保持已填写内容\n2. 生成待审批预约", "P1", "POSITIVE",
                List.of("场景法"), List.of("VALID_FLOW", "EXPECTED_BUSINESS_RESULT"), "CP1", "CD1", "预约提交",
                List.of("TP1"), List.of("用户需求"), List.of(), 0.9);

        List<String> result = (List<String>) quality.invoke(service, List.of(valid));

        assertTrue(result.isEmpty());
    }
}
