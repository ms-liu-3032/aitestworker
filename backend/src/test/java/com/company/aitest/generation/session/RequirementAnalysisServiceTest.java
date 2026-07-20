package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.BusinessException;
import com.company.aitest.generation.GenerationTaskCheckpointService;
import com.company.aitest.generation.GenerationTaskRecord;
import com.company.aitest.generation.GenerationTaskService;
import com.company.aitest.llm.gateway.LlmRuntimeException;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmStage;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.databind.JsonNode;
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
    void extractAnalysisJsonReadsTextualJsonAnalysisPayload() {
        String llmOutput = """
                {
                  "analysis": "{\\"requirement_understanding\\":\\"重新分析后的需求理解\\",\\"affected_modules\\":[\\"签到\\"]}",
                  "test_points": []
                }
                """;

        assertEquals("{\"requirement_understanding\":\"重新分析后的需求理解\",\"affected_modules\":[\"签到\"]}",
                RequirementAnalysisService.extractAnalysisJson(llmOutput));
    }

    @Test
    void extractAnalysisJsonFallsBackToRootAnalysisShape() {
        String llmOutput = """
                {
                  "requirement_understanding": "模型直接返回根级分析字段",
                  "affected_modules": ["签到管理"],
                  "coverage_matrix": []
                }
                """;

        assertEquals("{\"requirement_understanding\":\"模型直接返回根级分析字段\",\"affected_modules\":[\"签到管理\"],\"coverage_matrix\":[]}",
                RequirementAnalysisService.extractAnalysisJson(llmOutput));
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
    void extractAnalysisJsonSalvagesDisplayFieldsFromTruncatedOutput() throws Exception {
        String llmOutput = """
                {
                  "analysis": {
                    "requirement_understanding": "通过招投标系统反写业务系统，自动创建采购申请。",
                    "business_domain": "流程审批+招投标",
                    "requirement_type": "MIXED",
                    "input_sources": ["PRD_TEXT", "TOM"],
                    "affected_modules": ["采购申请", "会议室联动"],
                    "review_risk_questions": [
                """;

        String extracted = RequirementAnalysisService.extractAnalysisJson(llmOutput);
        assertNotNull(extracted);
        var root = objectMapper.readTree(extracted);
        assertEquals("通过招投标系统反写业务系统，自动创建采购申请。",
                root.path("requirement_understanding").asText());
        assertEquals("流程审批+招投标", root.path("business_domain").asText());
        assertEquals("MIXED", root.path("requirement_type").asText());
        assertEquals("PRD_TEXT", root.path("input_sources").get(0).asText());
        assertEquals("采购申请", root.path("affected_modules").get(0).asText());
        assertTrue(root.path("uncertain_items").get(0).asText().contains("可能被截断"));
    }

    @Test
    void shouldRequestAnalysisContinuationOnlyForNearLimitRecoverableTruncation() {
        String truncated = """
                {
                  "analysis": {
                    "requirement_understanding": "通过招投标系统反写业务系统，自动创建采购申请。",
                    "affected_modules": ["采购申请"]
                """;
        String complete = """
                {
                  "analysis": {
                    "requirement_understanding": "完整分析"
                  }
                }
                """;

        assertTrue(RequirementAnalysisService.shouldRequestAnalysisContinuation(truncated, 4092, 4096));
        assertFalse(RequirementAnalysisService.shouldRequestAnalysisContinuation(truncated, 100, 4096));
        assertFalse(RequirementAnalysisService.shouldRequestAnalysisContinuation(complete, 4092, 4096));
        assertFalse(RequirementAnalysisService.shouldRequestAnalysisContinuation("not-json", 4092, 4096));
    }

    @Test
    void shouldRequestContinuationForShortUnclosedJsonPrefixEvenWhenProviderUsageIsLow() {
        assertTrue(RequirementAnalysisService.shouldRequestAnalysisContinuation("{\"", 1, 2048));
        assertTrue(RequirementAnalysisService.shouldRequestAnalysisContinuation("{", 0, 2048));
        assertFalse(RequirementAnalysisService.shouldRequestAnalysisContinuation("[]", 0, 2048));
    }

    @Test
    void analysisStagePreservesGatewayErrorCodeForAsyncTaskClassification() throws Exception {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.invoke(any())).thenReturn(new LlmInvocationResponse(
                "request", "", 0, 0, 1L, null, null, null,
                LlmInvocationStatus.MODEL_ERROR, "PROVIDER_ERROR", "网络异常"));
        var service = new RequirementAnalysisService(
                null, null, null, gateway, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "invokeAnalysisStage", CurrentUser.class, Long.class, Long.class, Long.class, Long.class,
                String.class, String.class, String.class, int.class, String.class);
        method.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> method.invoke(
                service, new CurrentUser(1L, "tester", "USER"), 1L, 1L, 1L, null,
                "REQUIREMENT_ANALYSIS_CORE", "system", "user", 128, "需求理解"));
        LlmRuntimeException error = (LlmRuntimeException) thrown.getCause();
        assertEquals(com.company.aitest.llm.gateway.LlmErrorCode.PROVIDER_ERROR, error.errorCode());
        assertTrue(error.getMessage().contains("网络异常"));
    }

    @Test
    void asyncAnalysisRetryReusesCompletedNodeCheckpointInsteadOfCallingModelAgain() throws Exception {
        LlmGateway gateway = mock(LlmGateway.class);
        GenerationTaskService taskService = mock(GenerationTaskService.class);
        GenerationTaskCheckpointService checkpoints = mock(GenerationTaskCheckpointService.class);
        GenerationTaskRecord task = mock(GenerationTaskRecord.class);
        when(task.taskType()).thenReturn("REQUIREMENT_ANALYSIS");
        when(taskService.get(1L, 9L)).thenReturn(task);
        when(checkpoints.loadSucceededPayload(eq(9L), anyString()))
                .thenReturn(Optional.of("{\"analysis\":{\"requirement_understanding\":\"已完成\"}}"));
        var service = new RequirementAnalysisService(
                null, null, null, gateway, null, null, null, null, taskService, null, null, null, checkpoints
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "invokeAnalysisStage", CurrentUser.class, Long.class, Long.class, Long.class, Long.class,
                String.class, String.class, String.class, int.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service,
                new CurrentUser(1L, "tester", "USER"), 1L, 9L, 1L, null,
                "REQUIREMENT_ANALYSIS_CORE", "system", "user", 128, "需求理解");

        assertTrue(result.contains("已完成"));
        verify(gateway, times(0)).invoke(any());
        verify(checkpoints, times(1)).loadSucceededPayload(eq(9L), anyString());
    }

    @Test
    void invalidCompletedCheckpointIsDiscardedAndOnlyItsNodeIsRegenerated() throws Exception {
        LlmGateway gateway = mock(LlmGateway.class);
        GenerationTaskService taskService = mock(GenerationTaskService.class);
        GenerationTaskCheckpointService checkpoints = mock(GenerationTaskCheckpointService.class);
        GenerationTaskRecord task = mock(GenerationTaskRecord.class);
        when(task.taskType()).thenReturn("REQUIREMENT_ANALYSIS");
        when(taskService.get(1L, 10L)).thenReturn(task);
        when(checkpoints.loadSucceededPayload(eq(10L), anyString()))
                .thenReturn(Optional.of("{\"test_points\":["));
        when(gateway.invoke(any())).thenReturn(new LlmInvocationResponse(
                "fresh", "{\"test_points\":[]}", 0, 10, 1, null, null, null,
                LlmInvocationStatus.OK, null, null));
        var service = new RequirementAnalysisService(
                null, null, null, gateway, null, null, null, null, taskService, null, null, null, checkpoints
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "invokeAnalysisStage", CurrentUser.class, Long.class, Long.class, Long.class, Long.class,
                String.class, String.class, String.class, int.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(service,
                new CurrentUser(1L, "tester", "USER"), 1L, 10L, 1L, null,
                "REQUIREMENT_ANALYSIS_TEST_POINTS_1", "system", "user", 128, "测试点");

        assertEquals("{\"test_points\":[]}", result);
        verify(checkpoints).markFailed(eq(10L), anyString(),
                eq("OUTPUT_PARSE_ERROR"), anyString());
        verify(gateway, times(1)).invoke(any());
        verify(checkpoints).markSucceeded(eq(10L), anyString(), eq(result));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPointGenerationSplitsLargeCoverageSubjectByDimensionsWithoutDroppingAny() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("module", "审批通知");
        row.put("test_unit_ref", "U3");
        row.put("requirement_refs", List.of("R3"));
        for (String key : List.of("main_flow", "branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent")) {
            row.put(key, Map.of("count", 1, "items", List.of(key)));
        }
        row.put("total", 9);
        Method method = RequirementAnalysisService.class.getDeclaredMethod("partitionCoverageForTestPointNodes", List.class);
        method.setAccessible(true);
        List<List<Map<String, Object>>> slices = (List<List<Map<String, Object>>>) method.invoke(service, List.of(row));

        assertEquals(9, slices.size());
        int covered = slices.stream()
                .mapToInt(slice -> slice.stream().mapToInt(item -> List.of("main_flow", "branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent")
                        .stream().mapToInt(key -> ((Number) ((Map<?, ?>) item.get(key)).get("count")).intValue()).sum()).sum())
                .sum();
        assertEquals(9, covered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizesProviderCoverageAliasesAndRecalculatesMissingTotal() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method normalize = RequirementAnalysisService.class.getDeclaredMethod("normalizeCoverageMatrixJson", String.class);
        normalize.setAccessible(true);
        Method usable = RequirementAnalysisService.class.getDeclaredMethod("isUsableUnitCoverageMatrix", String.class);
        usable.setAccessible(true);
        String source = """
                [{
                  "module":"审批",
                  "main_flow":{"count":1,"items":["处理申请"]},
                  "exception":{"count":1,"items":["处理冲突"]},
                  "idempotency":{"count":2,"items":["重复请求","网络重试"]}
                }]
                """;

        String normalized = (String) normalize.invoke(service, source);
        List<Map<String, Object>> rows = objectMapper.readValue(normalized, List.class);
        Map<String, Object> row = rows.get(0);

        assertEquals(4, ((Number) row.get("total")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) row.get("idempotent")).get("count")).intValue());
        assertTrue((Boolean) usable.invoke(service, normalized));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coverageWorkItemsAreAdaptivelyBatchedWithoutDroppingAnyTestUnit() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        List<Map<String, Object>> units = new java.util.ArrayList<>();
        List<Map<String, Object>> atoms = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            units.add(Map.of("id", "U" + i, "name", "主题" + i,
                    "requirement_refs", List.of("R" + i), "depends_on_unit_refs", List.of()));
            atoms.add(Map.of("id", "R" + i, "title", "规则" + i,
                    "requirement", "验证规则" + i, "needs_clarification", false));
        }
        Method build = RequirementAnalysisService.class.getDeclaredMethod(
                "buildCoverageWorkItems", List.class, List.class);
        Method partition = RequirementAnalysisService.class.getDeclaredMethod(
                "partitionCoverageWorkItems", List.class);
        Method prompt = RequirementAnalysisService.class.getDeclaredMethod(
                "buildCoverageMatrixBatchUserPrompt", List.class, ProjectSemanticContextService.BuildResult.class);
        build.setAccessible(true);
        partition.setAccessible(true);
        prompt.setAccessible(true);

        List<?> workItems = (List<?>) build.invoke(service, units, atoms);
        List<List<?>> batches = (List<List<?>>) partition.invoke(service, workItems);

        assertEquals(9, workItems.size());
        assertEquals(3, batches.size());
        assertTrue(batches.stream().allMatch(batch -> batch.size() <= 4));
        String firstPrompt = (String) prompt.invoke(service, batches.get(0), null);
        assertTrue(firstPrompt.contains("U1"));
        assertTrue(firstPrompt.contains("R1"));
        assertTrue(firstPrompt.contains("逐项输出，不可遗漏"));
        assertTrue(firstPrompt.contains("文字保持紧凑，但不得删除"));
    }

    @Test
    void coverageQualityGateRejectsBatchThatOmitsATestUnit() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        List<Map<String, Object>> units = List.of(
                Map.of("id", "U1", "name", "提交", "requirement_refs", List.of("R1")),
                Map.of("id", "U2", "name", "审批", "requirement_refs", List.of("R2")));
        List<Map<String, Object>> atoms = List.of(
                Map.of("id", "R1", "title", "提交"), Map.of("id", "R2", "title", "审批"));
        Method build = RequirementAnalysisService.class.getDeclaredMethod(
                "buildCoverageWorkItems", List.class, List.class);
        Method gate = RequirementAnalysisService.class.getDeclaredMethod(
                "requireCoverageMatrixCoversAllWorkItems", List.class, List.class);
        build.setAccessible(true);
        gate.setAccessible(true);
        List<?> workItems = (List<?>) build.invoke(service, units, atoms);
        List<Map<String, Object>> rows = List.of(Map.of("test_unit_ref", "U1"));

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> gate.invoke(service, rows, workItems));
        assertTrue(thrown.getCause().getMessage().contains("U2"));
    }

    @Test
    void coverageWorkItemRequiresEveryAssignedRequirementReference() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        List<Map<String, Object>> units = List.of(Map.of(
                "id", "U1", "name", "审批", "requirement_refs", List.of("R1", "R2")));
        List<Map<String, Object>> atoms = List.of(
                Map.of("id", "R1", "title", "审批通过"), Map.of("id", "R2", "title", "审批驳回"));
        Method build = RequirementAnalysisService.class.getDeclaredMethod(
                "buildCoverageWorkItems", List.class, List.class);
        build.setAccessible(true);
        List<?> workItems = (List<?>) build.invoke(service, units, atoms);
        Method quality = java.util.Arrays.stream(RequirementAnalysisService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("isUsableCoverageForWorkItem"))
                .findFirst().orElseThrow();
        quality.setAccessible(true);
        Map<String, Object> dimension = Map.of("count", 1, "items", List.of("审批后状态正确"));
        Map<String, Object> empty = Map.of("count", 0, "items", List.of());
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("test_unit_ref", "U1");
        row.put("module", "审批");
        row.put("main_flow", dimension);
        row.put("exception", dimension);
        for (String key : List.of("branch", "boundary", "state", "data", "auth", "concurrency", "idempotent")) {
            row.put(key, empty);
        }
        row.put("total", 2);
        row.put("requirement_refs", List.of("R1"));

        assertFalse((Boolean) quality.invoke(service, List.of(row), workItems.get(0)));
        row.put("requirement_refs", List.of("R1", "R2"));
        assertTrue((Boolean) quality.invoke(service, List.of(row), workItems.get(0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coverageStageBatchesModelCallsWhilePreservingEveryUnitAndRequirementRef() throws Exception {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.invoke(any())).thenReturn(
                successfulCoverageResponse("req-1", 1, 4),
                successfulCoverageResponse("req-2", 5, 5));
        var service = new RequirementAnalysisService(
                null, null, null, gateway, null, null, null, null, null, null, null, null
        );
        List<Map<String, Object>> atoms = new java.util.ArrayList<>();
        List<Map<String, Object>> units = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            atoms.add(Map.of("id", "R" + i, "title", "规则" + i, "requirement", "验证规则" + i));
            units.add(Map.of("id", "U" + i, "name", "主题" + i,
                    "requirement_refs", List.of("R" + i), "depends_on_unit_refs", List.of()));
        }
        String coreJson = objectMapper.writeValueAsString(Map.of(
                "requirement_understanding", "五个测试主题", "requirement_atoms", atoms, "test_units", units));
        Method stage = RequirementAnalysisService.class.getDeclaredMethod(
                "runCoverageMatrixStages", CurrentUser.class, Long.class, Long.class, Long.class, Long.class,
                String.class, String.class, ProjectSemanticContextService.BuildResult.class);
        stage.setAccessible(true);

        Object result = stage.invoke(service, new CurrentUser(1L, "tester", "USER"),
                1L, null, 1L, null, "REQUIREMENT_ANALYSIS", coreJson, null);
        Method matrixAccessor = result.getClass().getDeclaredMethod("coverageMatrix");
        matrixAccessor.setAccessible(true);
        List<Map<String, Object>> rows = objectMapper.readValue((String) matrixAccessor.invoke(result), List.class);

        assertEquals(5, rows.size());
        assertEquals(List.of("U1", "U2", "U3", "U4", "U5"),
                rows.stream().map(row -> String.valueOf(row.get("test_unit_ref"))).toList());
        assertEquals(List.of("R1", "R2", "R3", "R4", "R5"),
                rows.stream().flatMap(row -> ((List<String>) row.get("requirement_refs")).stream()).toList());
        verify(gateway, times(2)).invoke(any());
    }

    private LlmInvocationResponse successfulCoverageResponse(String requestId, int start, int end) throws Exception {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) {
            Map<String, Object> populated = Map.of("count", 1, "items", List.of("验证规则" + i + "并检查结果"));
            Map<String, Object> empty = Map.of("count", 0, "items", List.of());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("test_unit_ref", "U" + i);
            row.put("requirement_refs", List.of("R" + i));
            row.put("module", "主题" + i);
            row.put("main_flow", populated);
            row.put("exception", populated);
            for (String key : List.of("branch", "boundary", "state", "data", "auth", "concurrency", "idempotent")) {
                row.put(key, empty);
            }
            row.put("total", 2);
            rows.add(row);
        }
        String content = objectMapper.writeValueAsString(Map.of("coverage_matrix", rows, "matrix_review_notes", List.of()));
        return new LlmInvocationResponse(requestId, content, 100, 50, 1L,
                null, null, null, LlmInvocationStatus.OK, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuralCaseCompositionUsesUpstreamPointsAsPreconditionsWithoutTreatingEveryDependencyAsAFlow() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, Object> submitUnit = Map.of("id", "U1", "name", "提交", "depends_on_unit_refs", List.of());
        Map<String, Object> approveUnit = Map.of("id", "U2", "name", "审批", "depends_on_unit_refs", List.of("U1"));
        Map<String, Object> submitPoint = new java.util.LinkedHashMap<>(Map.of(
                "id", "TP1", "title", "提交申请", "test_unit_ref", "U1", "point_type", "MAIN_FLOW",
                "description", "提交", "design_method", "场景法", "priority_hint", "CORE",
                "coverage_status", "SUPPORTED", "source_basis", List.of(), "needs_confirmation", false));
        Map<String, Object> approvePoint = new java.util.LinkedHashMap<>(Map.of(
                "id", "TP2", "title", "审批申请", "test_unit_ref", "U2", "point_type", "MAIN_FLOW",
                "description", "审批", "design_method", "场景法", "priority_hint", "CORE",
                "coverage_status", "SUPPORTED", "source_basis", List.of(), "needs_confirmation", false));
        Method method = RequirementAnalysisService.class.getDeclaredMethod("buildStructuralCasePlans", List.class, List.class);
        method.setAccessible(true);
        List<Map<String, Object>> plans = (List<Map<String, Object>>) method.invoke(service,
                List.of(submitUnit, approveUnit), List.of(submitPoint, approvePoint));

        Map<String, Object> approvalNode = plans.stream()
                .filter(plan -> "NODE_FOCUSED".equals(plan.get("case_strategy")))
                .filter(plan -> List.of("TP2").equals(plan.get("source_test_point_refs")))
                .findFirst().orElseThrow();
        assertEquals(List.of("TP1"), approvalNode.get("precondition_test_point_refs"));
        assertTrue(plans.stream().noneMatch(plan -> "FLOW_COMPOSED".equals(plan.get("case_strategy"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuralCaseCompositionClustersCohesiveTestPointsButKeepsEveryReferenceTraceable() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Map<String, Object> unit = Map.of("id", "U1", "name", "预约表单", "depends_on_unit_refs", List.of());
        Map<String, Object> empty = new java.util.LinkedHashMap<>(Map.of(
                "id", "TP1", "title", "姓名为空", "test_unit_ref", "U1", "point_type", "BOUNDARY",
                "description", "姓名为空时阻止提交", "design_method", "边界值", "priority_hint", "EXTENDED",
                "coverage_status", "SUPPORTED", "source_basis", List.of("需求"), "needs_confirmation", false));
        Map<String, Object> length = new java.util.LinkedHashMap<>(Map.of(
                "id", "TP2", "title", "姓名最大长度", "test_unit_ref", "U1", "point_type", "BOUNDARY",
                "description", "姓名达到最大长度时校验", "design_method", "边界值", "priority_hint", "EXTENDED",
                "coverage_status", "SUPPORTED", "source_basis", List.of("需求"), "needs_confirmation", false));
        Map<String, Object> format = new java.util.LinkedHashMap<>(Map.of(
                "id", "TP3", "title", "手机号格式", "test_unit_ref", "U1", "point_type", "BOUNDARY",
                "description", "手机号格式校验", "design_method", "边界值", "priority_hint", "HIGH",
                "coverage_status", "SUPPORTED", "source_basis", List.of("需求"), "needs_confirmation", false));
        Method method = RequirementAnalysisService.class.getDeclaredMethod("buildStructuralCasePlans", List.class, List.class);
        method.setAccessible(true);

        List<Map<String, Object>> plans = (List<Map<String, Object>>) method.invoke(service,
                List.of(unit), List.of(empty, length, format));

        assertEquals(1, plans.size());
        assertEquals(List.of("TP1", "TP2", "TP3"), plans.get(0).get("source_test_point_refs"));
        Map<String, Object> design = (Map<String, Object>) ((List<?>) plans.get(0).get("case_designs")).get(0);
        assertEquals(List.of("TP1", "TP2", "TP3"), design.get("source_test_point_refs"));
        assertEquals("HIGH", plans.get(0).get("priority_hint"));
    }

    @Test
    void mergeContinuationRemovesSimpleOverlap() {
        String original = "{\"analysis\":{\"requirement_understanding\":\"abc\",\"affected_modules\":[\"采购申请\",\"会议室联动流程";
        String continuation = "\"会议室联动流程\"]},\"test_points\":[]}";

        String merged = RequirementAnalysisService.mergeContinuation(original, continuation);
        assertTrue(merged.contains("\"affected_modules\":[\"采购申请\",\"会议室联动流程\"]"));
    }

    @Test
    void truncatedAnalysisKeepsRequestingAndMergingChunksUntilJsonCloses() throws Exception {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.invoke(any())).thenReturn(
                new LlmInvocationResponse("c1", "\"affected_modules\":[\"采购申请\"],", 0, 2048,
                        1, null, null, null, LlmInvocationStatus.OK, null, null),
                new LlmInvocationResponse("c2", "\"risk_scenarios\":[]}}", 0, 100,
                        1, null, null, null, LlmInvocationStatus.OK, null, null)
        );
        var service = new RequirementAnalysisService(
                null, null, null, gateway, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "continueAnalysisOutputIfNeeded", String.class, int.class, LlmInvocationRequest.class, String.class, int.class);
        method.setAccessible(true);
        String truncated = "{\"analysis\":{\"requirement_understanding\":\"采购申请\",";
        var request = new LlmInvocationRequest("root", 1L, 1L, 1L, "REQUIREMENT_ANALYSIS_CORE",
                LlmStage.REQ_CLARIFY, 1L, null, null, Map.of(), "system", "user", null, 4096);

        String merged = (String) method.invoke(service, truncated, 4096, request, "需求理解", 4096);

        assertNotNull(objectMapper.readTree(merged));
        assertEquals("采购申请", objectMapper.readTree(merged).path("analysis").path("affected_modules").get(0).asText());
        verify(gateway, times(2)).invoke(any());
    }

    @Test
    void emptyContinuationEnvelopeFailsInsteadOfCorruptingPreviousJson() throws Exception {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.invoke(any())).thenReturn(
                new LlmInvocationResponse("c1", "{\"continuation\":\"\"}", 0, 0,
                        1, null, null, null, LlmInvocationStatus.OK, null, null)
        );
        var service = new RequirementAnalysisService(
                null, null, null, gateway, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "continueAnalysisOutputIfNeeded", String.class, int.class, LlmInvocationRequest.class, String.class, int.class);
        method.setAccessible(true);
        var request = new LlmInvocationRequest("root", 1L, 1L, 1L, "REQUIREMENT_ANALYSIS_TEST_POINTS",
                LlmStage.REQ_CLARIFY, 1L, null, null, Map.of(), "system", "user", null, 4096);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> method.invoke(service, "{\"test_points\":[", 4096, request, "测试点", 4096));
        LlmRuntimeException error = (LlmRuntimeException) thrown.getCause();
        assertEquals(com.company.aitest.llm.gateway.LlmErrorCode.OUTPUT_PARSE_ERROR, error.errorCode());
        assertTrue(error.getMessage().contains("未返回有效片段"));
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
                "{\"requirement_understanding\":\"报销审批流\",\"test_point_scope_review\":{\"status\":\"CONFIRMED\"}}",
                null,
                "[]",
                "[{\"index\":0,\"answer\":\"审批节点为直属主管和财务\"}]",
                "[{\"assumption\":\"金额阈值按默认规则处理\"}]",
                "[{\"id\":\"TP1\",\"title\":\"提交报销单后进入主管审批\",\"generation_scope\":\"GENERATE\"}]",
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
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("只能为“已确认需要生成用例的测试点”生成用例"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("遵守三层递进"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("coverage_matrix"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("防冗余规则"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("design_method"));
    }

    @Test
    void draftTraceabilityPointsToAnalysisAssetInsteadOfCopyingAllTestPointsIntoEveryDraft() {
        var now = java.time.LocalDateTime.of(2026, 7, 16, 10, 0);
        var analysis = new RequirementAnalysisRecord(
                19L, 3L, 7, 0, "长需求", "{\"requirement_understanding\":\"x\"}",
                null, "[]", "[]", "[]", "["
                        + "{\"id\":\"TP1\",\"title\":\"第一个测试点\"},"
                        + "{\"id\":\"TP2\",\"title\":\"第二个测试点\"}]",
                null, null, null, "CONFIRMED", now, now);

        Map<String, Object> pointer = RequirementAnalysisService.buildDraftTestPointAssetPointer(analysis);

        assertEquals("requirement_analysis.test_points", pointer.get("storage"));
        assertEquals(19L, pointer.get("analysisId"));
        assertEquals(7, pointer.get("analysisVersion"));
        assertEquals(2, pointer.get("testPointCount"));
        assertFalse(pointer.toString().contains("第一个测试点"));
    }

    @Test
    void generationRequirementCarriesIndependentCasePlanAndStrategyRules() {
        var now = java.time.LocalDateTime.of(2026, 7, 10, 10, 0);
        var analysis = new RequirementAnalysisRecord(
                10L, 3L, 3, 0, "提交后进入审批并通知。",
                "{\"requirement_understanding\":\"审批流程\",\"test_point_scope_review\":{\"status\":\"CONFIRMED\"},\"case_plan\":[{\"id\":\"CP1\",\"case_strategy\":\"NODE_FOCUSED\",\"source_test_point_refs\":[\"TP2\"],\"precondition_test_point_refs\":[\"TP1\"]}]}",
                null, "[]", "[]", "[]", "[{\"id\":\"TP1\",\"title\":\"提交\",\"generation_scope\":\"REFERENCE_ONLY\"},{\"id\":\"TP2\",\"title\":\"审批\",\"generation_scope\":\"GENERATE\"}]",
                null, null, null, "CONFIRMED", now, now);

        String prompt = RequirementAnalysisService.buildGenerationRequirementText(analysis);

        assertTrue(prompt.contains("## 用例编排计划"));
        assertTrue(prompt.contains("CP1"));
        assertTrue(prompt.contains("仅作背景/前置参考的测试点"));
        assertTrue(prompt.contains("前序测试点只能写在前置条件"));
    }

    @Test
    void generationRequirementDoesNotTruncateTestPointOrCasePlanAssets() {
        var now = java.time.LocalDateTime.of(2026, 7, 10, 10, 0);
        String largeTitle = "尾部测试点-" + "x".repeat(12_000);
        var analysis = new RequirementAnalysisRecord(
                11L, 3L, 4, 0, "长需求", "{\"requirement_understanding\":\"长需求理解\",\"test_point_scope_review\":{\"status\":\"CONFIRMED\"},\"case_plan\":[{\"id\":\"CP99\",\"source_test_point_refs\":[\"TP99\"],\"case_designs\":[{\"id\":\"CD99\",\"scenario\":\"尾部设计\"}]}]}",
                null, "[]", "[]", "[]", "[{\"id\":\"TP99\",\"title\":\"" + largeTitle + "\",\"generation_scope\":\"GENERATE\"}]",
                null, null, null, "CONFIRMED", now, now);

        String prompt = RequirementAnalysisService.buildGenerationRequirementText(analysis);

        assertTrue(prompt.contains(largeTitle));
        assertTrue(prompt.contains("CP99"));
        assertTrue(prompt.contains("CD99"));
    }

    @Test
    void generationRequiresHumanTestPointScopeConfirmation() {
        var now = java.time.LocalDateTime.of(2026, 7, 18, 10, 0);
        var analysis = new RequirementAnalysisRecord(
                12L, 3L, 5, 0, "需求", "{\"requirement_understanding\":\"待审核\"}",
                null, "[]", "[]", "[]", "[{\"id\":\"TP1\",\"title\":\"测试点\",\"generation_scope\":\"GENERATE\"}]",
                null, null, null, "CONFIRMED", now, now);

        BusinessException error = assertThrows(BusinessException.class,
                () -> RequirementAnalysisService.buildGenerationRequirementText(analysis));

        assertTrue(error.getMessage().contains("确认测试点范围"));
    }

    @Test
    void generationExcludesReferenceAndDiscardedPointsFromCasePlans() {
        var now = java.time.LocalDateTime.of(2026, 7, 18, 10, 0);
        var analysis = new RequirementAnalysisRecord(
                13L, 3L, 6, 0, "需求",
                "{\"test_point_scope_review\":{\"status\":\"CONFIRMED\"},\"case_plan\":["
                        + "{\"id\":\"CP1\",\"case_strategy\":\"NODE_FOCUSED\",\"source_test_point_refs\":[\"TP1\"]},"
                        + "{\"id\":\"CP2\",\"case_strategy\":\"NODE_FOCUSED\",\"source_test_point_refs\":[\"TP2\"]},"
                        + "{\"id\":\"CP3\",\"case_strategy\":\"NODE_FOCUSED\",\"source_test_point_refs\":[\"TP3\"]}]}",
                null, "[]", "[]", "[]",
                "[{\"id\":\"TP1\",\"title\":\"本期变更\",\"generation_scope\":\"GENERATE\"},"
                        + "{\"id\":\"TP2\",\"title\":\"背景参考\",\"generation_scope\":\"REFERENCE_ONLY\"},"
                        + "{\"id\":\"TP3\",\"title\":\"明确排除\",\"generation_scope\":\"EXCLUDED\"}]",
                null, null, null, "CONFIRMED", now, now);

        String prompt = RequirementAnalysisService.buildGenerationRequirementText(analysis);

        assertTrue(prompt.contains("本期变更"));
        assertTrue(prompt.contains("背景参考"));
        assertFalse(prompt.contains("明确排除"));
        assertTrue(prompt.contains("CP1"));
        assertFalse(prompt.contains("CP2"));
        assertFalse(prompt.contains("CP3"));
    }

    @Test
    void confirmedRequirementScopeFiltersMatrixInputWithoutLosingReferenceAudit() throws Exception {
        var service = new RequirementAnalysisService(null, null, null, null, null, null,
                null, null, null, null, null, null);
        Method method = RequirementAnalysisService.class.getDeclaredMethod("buildGenerationCore", String.class);
        method.setAccessible(true);
        String input = """
                {
                  "requirement_understanding":"本期新增审批，历史报表仅作背景",
                  "requirement_scope_review":{"status":"CONFIRMED"},
                  "requirement_atoms":[
                    {"id":"R1","requirement":"新增审批","generation_scope":"GENERATE"},
                    {"id":"R2","requirement":"历史报表","generation_scope":"REFERENCE_ONLY"},
                    {"id":"R3","requirement":"下期导出","generation_scope":"EXCLUDED"}
                  ],
                  "test_units":[
                    {"id":"U1","name":"审批","requirement_refs":["R1","R2"],"depends_on_unit_refs":["U2"]},
                    {"id":"U2","name":"导出","requirement_refs":["R3"],"depends_on_unit_refs":[]}
                  ]
                }
                """;

        String filtered = (String) method.invoke(service, input);
        var root = objectMapper.readTree(filtered);

        assertEquals(1, root.path("requirement_atoms").size());
        assertEquals("R1", root.path("requirement_atoms").get(0).path("id").asText());
        assertEquals(1, root.path("reference_requirement_atoms").size());
        assertEquals("R2", root.path("reference_requirement_atoms").get(0).path("id").asText());
        assertEquals(1, root.path("test_units").size());
        assertEquals(List.of("R1"), StreamSupport.stream(
                root.path("test_units").get(0).path("requirement_refs").spliterator(), false)
                .map(JsonNode::asText).toList());
        assertTrue(root.path("test_units").get(0).path("depends_on_unit_refs").isEmpty());
        assertFalse(filtered.contains("R3"));
    }

    @Test
    void casePlanMustCoverEveryStableTestPointBeforeGenerationCanStart() throws Exception {
        var service = new RequirementAnalysisService(null, null, null, null, null, null, null, null, null, null, null, null);
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "missingTestPointIdsFromCasePlan", String.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) method.invoke(service,
                "[{\"id\":\"TP1\"},{\"id\":\"TP2\"}]",
                "[{\"id\":\"CP1\",\"source_test_point_refs\":[\"TP1\"]}]");

        assertEquals(List.of("TP2"), missing);
    }

    @Test
    void nodeFocusedPlanAllowsCohesiveCurrentTestPointClusterButStillRequiresCaseDesign() throws Exception {
        var service = new RequirementAnalysisService(null, null, null, null, null, null, null, null, null, null, null, null);
        Method method = RequirementAnalysisService.class.getDeclaredMethod("validateNodeCasePlans",
                String.class, Map.class, List.class, List.class, List.class);
        method.setAccessible(true);
        Map<String, Object> unit = Map.of("id", "U2", "depends_on_unit_refs", List.of("U1"));
        List<Map<String, Object>> points = List.of(
                Map.of("id", "TP1", "test_unit_ref", "U1"),
                Map.of("id", "TP2", "test_unit_ref", "U2"),
                Map.of("id", "TP3", "test_unit_ref", "U2"));

        @SuppressWarnings("unchecked")
        List<String> problems = (List<String>) method.invoke(service,
                "[{\"case_strategy\":\"NODE_FOCUSED\",\"source_test_point_refs\":[\"TP2\",\"TP3\"],\"case_designs\":[]}]",
                unit, points, List.of(unit), List.of(points.get(1), points.get(2)));

        assertFalse(problems.stream().anyMatch(problem -> problem.contains("至少引用一个")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("case_designs")));
    }

    @Test
    void longRequirementIsSplitWithoutDroppingMiddleContent() throws Exception {
        var service = new RequirementAnalysisService(null, null, null, null, null, null, null, null, null, null, null, null);
        Method method = RequirementAnalysisService.class.getDeclaredMethod("splitRequirementIntoFragments", String.class);
        method.setAccessible(true);
        String first = "第一段需求：提交申请后进入审批。".repeat(120);
        String middle = "中间关键规则：审批通过后发送通知并写入审计记录。";
        String last = "最后一段需求：取消后回收权限。".repeat(120);

        @SuppressWarnings("unchecked")
        List<String> fragments = (List<String>) method.invoke(service, first + middle + last);

        assertTrue(fragments.size() > 1);
        assertTrue(String.join("", fragments).contains(middle));
        assertEquals(first + middle + last, String.join("", fragments));
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
                "{\"requirement_understanding\":\"禁止申请人到访配置\"}",
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

    @Test
    void clarificationGateDerivesQuestionForRequirementAtomMarkedAsNeedingClarification() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "ensureClarificationQuestions", String.class, String.class);
        method.setAccessible(true);

        String questions = (String) method.invoke(service, """
                {"requirement_atoms":[
                  {"id":"R7","title":"审批规则","requirement":"仅说明需要审批，未说明审批人和驳回后的处理","needs_clarification":true}
                ]}
                """, "[]");

        var parsed = objectMapper.readTree(questions);
        assertEquals(1, parsed.size());
        assertTrue(parsed.get(0).path("question").asText().contains("审批规则"));
        assertEquals("R7", parsed.get(0).path("source_requirement_ref").asText());
        assertFalse(parsed.get(0).path("reason").asText().isBlank());
        assertFalse(parsed.get(0).path("impact").asText().isBlank());
    }

    @Test
    void clarificationGatePromotesAnswerableReviewQuestionWithoutMixingStoredFields() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "ensureClarificationQuestions", String.class, String.class);
        method.setAccessible(true);

        String questions = (String) method.invoke(service, """
                {"review_risk_questions":[{
                  "question":"审批被驳回后是否允许修改并重新提交？",
                  "reason":"状态回退规则未说明",
                  "impact":"影响审批流程测试"
                }]}
                """, "[]");

        var parsed = objectMapper.readTree(questions);
        assertEquals(1, parsed.size());
        assertEquals("REVIEW_RISK", parsed.get(0).path("source_type").asText());
        assertTrue(parsed.get(0).path("question").asText().contains("重新提交"));
    }

    @Test
    void enrichAnalysisResultPromotesNestedAnalysisFieldsForDisplay() throws Exception {
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
                """
                {
                  "analysis": {
                    "requirement_understanding": "管理员配置禁用登录规则，命中后登录被拦截。",
                    "affected_modules": ["系统管理", "用户登录"],
                    "review_risk_questions": [
                      { "question": "规则入口在哪里？", "reason": "影响测试路径" }
                    ]
                  }
                }
                """,
                null,
                null
        );

        var root = objectMapper.readTree(output);
        assertEquals("管理员配置禁用登录规则，命中后登录被拦截。",
                root.path("requirement_understanding").asText());
        assertEquals("系统管理", root.path("affected_modules").get(0).asText());
        assertEquals("规则入口在哪里？", root.path("review_risk_questions").get(0).path("question").asText());
    }

    @Test
    void ensureUsableAnalysisResultRejectsEmptyAnalysisPayload() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method enrich = RequirementAnalysisService.class.getDeclaredMethod(
                "enrichAnalysisResult",
                String.class,
                ProjectSemanticContextService.BuildResult.class,
                String.class
        );
        enrich.setAccessible(true);
        Method ensure = RequirementAnalysisService.class.getDeclaredMethod("ensureUsableAnalysisResult", String.class);
        ensure.setAccessible(true);

        String output = (String) enrich.invoke(service, "{}", null, null);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> ensure.invoke(service, output)
        );
        assertTrue(ex.getCause() instanceof LlmRuntimeException);
        assertTrue(ex.getCause().getMessage().contains("未包含可展示的需求分析字段"));
    }

    @Test
    void coreAnalysisWithoutRequirementAtomsIsRejectedInsteadOfFallingBack() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "requireRequirementAtoms", String.class
        );
        method.setAccessible(true);
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(service, "{\"requirement_understanding\":\"仅有摘要\"}")
        );
        assertTrue(ex.getCause() instanceof LlmRuntimeException);
        assertTrue(ex.getCause().getMessage().contains("requirement_atoms"));
    }

    @Test
    void normalizeChangeScopeKeepsDatabaseEnumShort() {
        assertEquals("MINOR", RequirementAnalysisService.normalizeChangeScope("MINOR"));
        assertEquals("MAJOR", RequirementAnalysisService.normalizeChangeScope("MAJOR"));
        assertEquals("MINOR", RequirementAnalysisService.normalizeChangeScope("本次只是小幅描述补充，不新增模块页面流程"));
        assertEquals("MAJOR", RequirementAnalysisService.normalizeChangeScope("本次补充涉及员工小程序、申请人自助申请、接口反写等多个模块，影响范围较大，需要按重大变更处理。"));
        assertEquals("MAJOR", RequirementAnalysisService.normalizeChangeScope("无法判断的长句也不能直接写入数据库字段导致 Data too long"));
        assertNull(RequirementAnalysisService.normalizeChangeScope(null));
        assertNull(RequirementAnalysisService.normalizeChangeScope(" "));
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

    @SuppressWarnings("unchecked")
    @Test
    void groupedDraftReferencesCoverEveryTestPointWithoutTitleHeuristics() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "findUncoveredTestPoints", String.class, List.class);
        method.setAccessible(true);

        List<String> uncovered = (List<String>) method.invoke(service,
                "[{\"id\":\"TP1\",\"title\":\"提交预约\"},{\"id\":\"TP2\",\"title\":\"审批预约\"},{\"id\":\"TP3\",\"title\":\"发送通知\"}]",
                List.of("{\"sourceTestPoint\":\"提交预约\",\"sourceTestPointRefs\":[\"TP1\",\"TP2\",\"TP3\"]}"));

        assertTrue(uncovered.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void coverageCheckReportsOnlyActuallyMissingStableIds() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "findUncoveredTestPoints", String.class, List.class);
        method.setAccessible(true);

        List<String> uncovered = (List<String>) method.invoke(service,
                "[{\"id\":\"TP1\",\"title\":\"权限状态\"},{\"id\":\"TP2\",\"title\":\"权限状态冻结记录\"}]",
                List.of("{\"sourceTestPoint\":\"权限状态\",\"sourceTestPointRefs\":[\"TP1\"]}"));

        assertEquals(List.of("TP2 权限状态冻结记录"), uncovered);
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
        when(mockSemantic.build(any(Long.class), captor.capture(), any(List.class), any(int.class), any()))
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
        org.mockito.Mockito.verify(mockSemantic).build(any(Long.class), anyString(), any(List.class), any(int.class), any());
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
        assertTrue(root.has("coverage_matrix"));
        assertTrue(root.has("skill_self_check"));

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
        assertTrue(root.path("coverage_matrix").isArray());
        assertTrue(root.path("skill_self_check").isObject());
        assertFalse(root.path("skill_self_check").path("three_layer_complete").asBoolean());
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
        assertEquals("FUNCTIONAL", tp.get("skill_layer").asText());
        assertEquals("场景法", tp.get("design_method").asText());
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
        assertTrue(prompt.contains("coverage_matrix"));
        assertTrue(prompt.contains("\"count\""));
        assertTrue(prompt.contains("\"items\""));
        assertTrue(prompt.contains("不能只返回数字"));
        assertTrue(prompt.contains("coverage_matrix 最多 6 个模块"));
        assertTrue(prompt.contains("test_points 最多 24 条"));
        assertTrue(prompt.contains("优先保证 JSON 完整闭合"));
        assertTrue(prompt.contains("skill_self_check"));
        assertTrue(prompt.contains("skill_layer"));
        assertTrue(prompt.contains("design_method"));
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
        assertEquals("BOUNDARY_SUPPLEMENT", tp.get("skill_layer").asText());
        assertEquals("边界值", tp.get("design_method").asText());
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
        assertTrue(prompt.contains("判定表"));
        assertTrue(prompt.contains("等价类"));
        assertTrue(prompt.contains("状态迁移"));
        // 验证原型/页面控件不等于测试范围的规则
        assertTrue(prompt.contains("原型/页面上已有控件，不等于本次新增测试范围"));
        // 验证证据伪装规则
        assertTrue(prompt.contains("不允许伪装成已确认事实"));
        // 验证 Skill 方法论吸收规则
        assertTrue(prompt.contains("测试点必须按三层递进拆解"));
        assertTrue(prompt.contains("FUNCTIONAL"));
        assertTrue(prompt.contains("EXCEPTION"));
        assertTrue(prompt.contains("BOUNDARY_SUPPLEMENT"));
        assertTrue(prompt.contains("防冗余自检"));
        assertTrue(prompt.contains("状态参数化"));
        assertTrue(prompt.contains("字段校验组"));
        assertTrue(prompt.contains("P0/CORE 只用于核心业务主路径"));
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

    @Test
    void stagedPromptsPreventCrossStageDrift() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method matrix = RequirementAnalysisService.class.getDeclaredMethod("buildCoverageMatrixSystemPrompt");
        matrix.setAccessible(true);
        Method points = RequirementAnalysisService.class.getDeclaredMethod("buildTestPointsSystemPrompt");
        points.setAccessible(true);
        Method core = RequirementAnalysisService.class.getDeclaredMethod("buildAnalysisCoreSystemPrompt", boolean.class);
        core.setAccessible(true);

        String corePrompt = (String) core.invoke(service, false);
        String matrixPrompt = (String) matrix.invoke(service);
        String pointsPrompt = (String) points.invoke(service);

        assertTrue(corePrompt.contains("严禁只返回 test_points"));
        assertTrue(matrixPrompt.contains("第 1 阶段 analysis"));
        assertTrue(matrixPrompt.contains("不得改写需求理解"));
        assertTrue(matrixPrompt.contains("不能自行改成另一个需求"));
        assertTrue(matrixPrompt.contains("不生成测试点和测试用例"));
        assertTrue(matrixPrompt.contains("覆盖矩阵是后续生成用例的主依据"));
        assertTrue(matrixPrompt.contains("不得只返回“主流程 + 异常”两个维度"));

        assertTrue(pointsPrompt.contains("第 1 阶段 analysis"));
        assertTrue(pointsPrompt.contains("第 2 阶段 coverage_matrix"));
        assertTrue(pointsPrompt.contains("不得改写需求理解"));
        assertTrue(pointsPrompt.contains("必须覆盖 coverage_matrix"));
        assertTrue(pointsPrompt.contains("不生成测试用例"));
        assertTrue(pointsPrompt.contains("每个需求原子（包括 needs_clarification=true）至少要有一个测试点"));
        assertTrue(pointsPrompt.contains("requirement_refs"));
    }

    @Test
    void requirementAtomCoverageRejectsTestPointsThatOnlyCoverPartOfTheRequirement() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method method = RequirementAnalysisService.class.getDeclaredMethod(
                "missingRequirementAtomIds", List.class, String.class);
        method.setAccessible(true);
        List<Map<String, Object>> atoms = List.of(
                Map.of("id", "R1", "needs_clarification", false),
                Map.of("id", "R2", "needs_clarification", false),
                Map.of("id", "R3", "needs_clarification", true)
        );
        String points = "[{\"title\":\"预约提交\",\"requirement_refs\":[\"R1\"]}]";
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) method.invoke(service, atoms, points);
        assertEquals(List.of("R2", "R3"), missing);
    }

    @Test
    void coreAnalysisRejectsAnyRequirementAtomNotAssignedToATestUnitIncludingClarificationItems() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method missing = RequirementAnalysisService.class.getDeclaredMethod("missingTestUnitRequirementAtomIds", String.class);
        missing.setAccessible(true);
        String core = """
                {"requirement_atoms":[
                  {"id":"R1","needs_clarification":false},
                  {"id":"R2","needs_clarification":true}
                ],"test_units":[{"id":"U1","requirement_refs":["R1"]}]}
                """;

        @SuppressWarnings("unchecked")
        List<String> uncovered = (List<String>) missing.invoke(service, core);

        assertEquals(List.of("R2"), uncovered);
    }

    @Test
    void corePatchOnlyReplacesMissingAssetsAndKeepsCompletedCoreFields() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method missing = RequirementAnalysisService.class.getDeclaredMethod("missingCoreFields", String.class);
        Method merge = RequirementAnalysisService.class.getDeclaredMethod(
                "mergeCorePatch", String.class, String.class, List.class);
        missing.setAccessible(true);
        merge.setAccessible(true);

        String initial = """
                {"requirement_understanding":"采购申请后需要审批","business_domain":"申请人",
                 "requirement_atoms":[{"id":"R1","title":"提交预约"}],"test_units":[]}
                """;
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) missing.invoke(service, initial);
        assertEquals(List.of("test_units"), fields);

        String patched = (String) merge.invoke(service, initial, """
                {"requirement_understanding":"不应覆盖", "test_units":[{
                  "id":"U1","name":"预约审批","requirement_refs":["R1"],"summary":"审批","depends_on_unit_refs":[]
                }]}
                """, fields);
        var root = objectMapper.readTree(patched);
        assertEquals("采购申请后需要审批", root.path("requirement_understanding").asText());
        assertEquals(1, root.path("requirement_atoms").size());
        assertEquals("U1", root.path("test_units").get(0).path("id").asText());
    }

    @Test
    void finalTestUnitRepairPromptIsNarrowAndRequiresFullRequirementAtomCoverage() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method systemPrompt = RequirementAnalysisService.class.getDeclaredMethod("buildTestUnitsRepairSystemPrompt");
        Method userPrompt = RequirementAnalysisService.class.getDeclaredMethod("buildTestUnitsRepairPrompt", String.class);
        systemPrompt.setAccessible(true);
        userPrompt.setAccessible(true);

        String system = (String) systemPrompt.invoke(service);
        String user = (String) userPrompt.invoke(service, """
                {"business_domain":"申请人","requirement_understanding":"预约与审批",
                 "requirement_atoms":[{"id":"R1","title":"提交预约"},{"id":"R2","title":"审批"}],
                 "test_units":[]}
                """);

        assertTrue(system.contains("只根据已确认的 requirement_atoms"));
        assertTrue(system.contains("每个现有 R 编号都必须至少被一个"));
        assertTrue(system.contains("不要返回任何其他字段"));
        assertTrue(user.contains("R1"));
        assertTrue(user.contains("R2"));
        assertTrue(user.contains("请只返回 test_units"));
    }

    @Test
    void testUnitRepairRetriesMalformedSmallNodeWithinTheSameTask() throws Exception {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.invoke(any())).thenReturn(
                new LlmInvocationResponse("repair-1", "{\"test_units\":[]}",
                        10, 4, 1L, null, null, null, LlmInvocationStatus.OK, null, null),
                new LlmInvocationResponse("repair-2", """
                        {"test_units":[
                          {"id":"U1","name":"预约审批","requirement_refs":["R1","R2"],
                           "summary":"预约提交与审批","depends_on_unit_refs":[]}
                        ]}
                        """, 10, 10, 1L, null, null, null, LlmInvocationStatus.OK, null, null)
        );
        var service = new RequirementAnalysisService(
                null, null, null, gateway, null, null, null, null, null, null, null, null
        );
        Method repair = RequirementAnalysisService.class.getDeclaredMethod(
                "repairTestUnitsIfNeeded", CurrentUser.class, Long.class, Long.class, Long.class,
                Long.class, String.class, String.class, String.class);
        repair.setAccessible(true);

        String repaired = (String) repair.invoke(service, new CurrentUser(7L, "tester", "USER"),
                9L, null, 1L, null, "REQUIREMENT_ANALYSIS_CORE", """
                        {"requirement_understanding":"预约后审批","requirement_atoms":[
                          {"id":"R1","title":"提交预约"},{"id":"R2","title":"审批"}
                        ],"test_units":[]}
                        """, "需求理解");

        var root = objectMapper.readTree(repaired);
        assertEquals(1, root.path("test_units").size());
        assertEquals(List.of("R1", "R2"), objectMapper.convertValue(
                root.path("test_units").get(0).path("requirement_refs"), List.class));
        verify(gateway, times(2)).invoke(any());
    }

    @Test
    void extractAnalysisJsonAcceptsTopLevelSingleFieldPatchPayload() {
        String atomsOnly = """
                {"requirement_atoms":[{"id":"R1","title":"提交预约"}]}
                """;
        String unitsOnly = """
                {"test_units":[{"id":"U1","name":"预约","requirement_refs":["R1"]}]}
                """;

        assertTrue(RequirementAnalysisService.extractAnalysisJson(atomsOnly).contains("requirement_atoms"));
        assertTrue(RequirementAnalysisService.extractAnalysisJson(unitsOnly).contains("test_units"));
    }

    @Test
    void unverifiedCrossFragmentDependenciesKeepAllTestUnitsForNodeFocusedGeneration() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Class<?> coreResultType = Class.forName(
                "com.company.aitest.generation.session.RequirementAnalysisService$CoreStageResult");
        var constructor = coreResultType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object core = constructor.newInstance(
                "{}",
                """
                {"requirement_atoms":[{"id":"R1"},{"id":"R2"}],"test_units":[
                  {"id":"U1","name":"预约","requirement_refs":["R1"],"depends_on_unit_refs":["U2"]},
                  {"id":"U2","name":"审批","requirement_refs":["R2"],"depends_on_unit_refs":["U1"]}
                ],"uncertain_items":[]}
                """,
                "[]", "[]", "[]", null, "[]");

        Method clear = RequirementAnalysisService.class.getDeclaredMethod(
                "clearInferredUnitDependencies", coreResultType, String.class, String.class);
        clear.setAccessible(true);
        Object cleared = clear.invoke(service, core, "依赖方向不确定，需人工确认。", "{}");
        Method coreJson = coreResultType.getDeclaredMethod("coreJson");
        coreJson.setAccessible(true);
        var root = objectMapper.readTree((String) coreJson.invoke(cleared));

        assertEquals(2, root.path("test_units").size());
        assertEquals(0, root.path("test_units").get(0).path("depends_on_unit_refs").size());
        assertEquals(0, root.path("test_units").get(1).path("depends_on_unit_refs").size());
        assertTrue(root.path("uncertain_items").toString().contains("依赖方向不确定"));
    }

    @Test
    void outOfScopeRequirementSuggestionDefaultsToExcludedWithoutDeletingAsset() throws Exception {
        var service = new RequirementAnalysisService(
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        Method initialize = RequirementAnalysisService.class.getDeclaredMethod(
                "initializeRequirementAtomScopes", String.class);
        initialize.setAccessible(true);
        String result = (String) initialize.invoke(service, """
                {"requirement_atoms":[
                  {"id":"R1","title":"本期审批","scope_recommendation":"IN_SCOPE"},
                  {"id":"R2","title":"下期导出","scope_recommendation":"OUT_OF_SCOPE"}
                ],"test_units":[]}
                """);
        var root = objectMapper.readTree(result);

        assertEquals(2, root.path("requirement_atoms").size());
        assertEquals("GENERATE", root.path("requirement_atoms").get(0).path("generation_scope").asText());
        assertEquals("EXCLUDED", root.path("requirement_atoms").get(1).path("generation_scope").asText());
    }
}
