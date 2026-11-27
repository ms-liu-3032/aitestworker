package com.company.aitest.dsh;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AutomationAssetServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AutomationAssetService service = new AutomationAssetService(mock(JdbcClient.class),
            mock(JdbcTemplate.class), mapper, mock(TimeProvider.class), mock(ProjectAccessService.class),
            mock(DshWorkSessionService.class), mock(DshCaptureService.class));

    @Test
    void batchPublicationRejectsInvalidAndDuplicateTargets() {
        assertThrows(BusinessException.class, () -> service.publishReusableBatch(1L,
                new AutomationAssetService.BatchPublicationInput(java.util.List.of()), null));
        assertThrows(BusinessException.class, () -> service.publishReusableBatch(1L,
                new AutomationAssetService.BatchPublicationInput(java.util.stream.LongStream.rangeClosed(1, 101)
                        .mapToObj(id -> new AutomationAssetService.PublicationTarget(id, 1)).toList()), null));
        assertThrows(BusinessException.class, () -> service.publishReusableBatch(1L,
                new AutomationAssetService.BatchPublicationInput(java.util.List.of(
                        new AutomationAssetService.PublicationTarget(7L, 1),
                        new AutomationAssetService.PublicationTarget(7L, 1))), null));
        assertThrows(BusinessException.class, () -> service.publishReusableBatch(1L,
                new AutomationAssetService.BatchPublicationInput(java.util.List.of(
                        new AutomationAssetService.PublicationTarget(7L, 0))), null));
    }

    @Test
    void wheelScrollRequiresBoundedNumericDistance() throws Exception {
        JsonNode valid = mapper.readTree("""
                {"caseNo":"TC-001","page":"/picker","steps":[{"type":"scroll",
                 "locator":{"by":"css","value":".am-picker-col"},"deltaY":120,"repeat":2}],
                 "assertions":[{"by":"text","value":"已选择日期"}]}
                """);
        assertDoesNotThrow(() -> service.canonical(valid, "TC-001"));
        var invalid = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("steps").get(0)).put("deltaY", 0);
        assertThrows(BusinessException.class, () -> service.canonical(invalid, "TC-001"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("steps").get(0)).put("deltaY", 2001);
        assertThrows(BusinessException.class, () -> service.canonical(invalid, "TC-001"));
    }

    @Test
    void dragRequiresBoundedDisplacementAndMoveCount() throws Exception {
        JsonNode valid = mapper.readTree("""
                {"caseNo":"TC-001","page":"/picker","steps":[{"type":"drag",
                 "locator":{"by":"css","value":".picker-column"},"deltaY":-120,"dragSteps":12}],
                 "assertions":[{"by":"text","value":"已选择日期"}]}
                """);
        assertDoesNotThrow(() -> service.canonical(valid, "TC-001"));
        var step = (com.fasterxml.jackson.databind.node.ObjectNode) valid.path("steps").get(0);
        step.put("deltaY", 0);
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001"));
        step.put("deltaY", -120);
        step.put("dragSteps", 51);
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001"));
        step.put("dragSteps", 12);
        step.put("deltaX", 2001);
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001"));
    }

    @Test
    void fixedWaitNeedsBoundedDurationWithoutLocatorAndHasTotalBudget() throws Exception {
        JsonNode valid = mapper.readTree("""
                {"caseNo":"TC-001","page":"/async-reset","steps":[
                  {"type":"click","locator":{"by":"text","value":"重置"}},
                  {"type":"wait","timeoutMs":500}],
                 "assertions":[{"by":"text","value":"重置完成"}]}
                """);
        assertDoesNotThrow(() -> service.canonical(valid, "TC-001"));
        var step = (com.fasterxml.jackson.databind.node.ObjectNode) valid.path("steps").get(1);
        step.put("timeoutMs", "500");
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001"));
        step.put("timeoutMs", 30_001);
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001"));
        step.put("timeoutMs", 500);
        step.set("locator", mapper.readTree("{\"by\":\"css\",\"value\":\"body\"}"));
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001"));
        step.remove("locator");
        var many = (com.fasterxml.jackson.databind.node.ArrayNode) valid.path("steps");
        many.add(mapper.readTree("{\"type\":\"wait\",\"timeoutMs\":30000}"));
        many.add(mapper.readTree("{\"type\":\"wait\",\"timeoutMs\":30000}"));
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001"));
    }

    @Test
    void reuseDiscoveryRejectsMissingScenarioAndUnboundedPages() {
        var user = mock(com.company.aitest.common.CurrentUser.class);
        assertThrows(BusinessException.class, () -> service.reuseCandidates(18L, "", "UI", 0, 50, user));
        assertThrows(BusinessException.class, () -> service.reuseCandidates(18L, "访客", "UI", -1, 50, user));
        assertThrows(BusinessException.class, () -> service.reuseCandidates(18L, "访客", "UI", 0, 101, user));
        assertThrows(BusinessException.class, () -> service.adoptReusable(18L, 7L, null, user));
    }

    private JsonNode mapping(String stepLocator, String data, String assertionText) throws Exception {
        return mapper.readTree("""
                {"caseNo":"TC-001","page":"/visit/newInvitation","steps":[
                  {"type":"click","locator":{"by":"text","value":"%s","fallback":[{"by":"placeholder","value":"请添加访客"}]}},
                  {"type":"fill","locator":{"by":"placeholder","value":"请输入访客手机"},"data":"%s"}],
                 "assertions":[{"by":"text","value":"%s","timeout":10000}]}
                """.formatted(stepLocator, data, assertionText));
    }

    @Test
    void mappingRequiresCaseOwnershipAndRuntimePhoneParameter() throws Exception {
        JsonNode valid = mapping("请添加访客", "${phone13}", "邀请成功");
        assertTrue(service.canonical(valid, "TC-001").contains("phone13"));
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-002"));
        assertThrows(BusinessException.class, () -> service.canonical(
                mapping("请添加访客", "13800138000", "邀请成功"), "TC-001"));
    }

    @Test
    void requiredFieldMappingNeedsAnActualEmptyInputAndTwoObservableAssertions() throws Exception {
        JsonNode negative = mapper.readTree("""
                {"caseNo":"TC-001","page":"/form","steps":[
                  {"type":"fill","locator":{"by":"label","value":"姓名"},"data":""},
                  {"type":"click","locator":{"by":"role","value":"button","name":"提交"}}],
                 "assertions":[{"by":"text","value":"姓名必填","state":"visible"},
                   {"by":"text","value":"提交成功","state":"hidden"}],
                 "uiCoverage":{"ruleCode":"UI_REQUIRED_FIELD","fieldKey":"name","role":"NEGATIVE",
                   "fieldStepIndex":0,"submitStepIndex":1,"errorAssertionIndex":0,"noSuccessAssertionIndex":1}}
                """);
        assertDoesNotThrow(() -> service.canonical(negative, "TC-001"));
        JsonNode missingNoSuccess = negative.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingNoSuccess.path("assertions").get(1)).put("state", "visible");
        assertThrows(BusinessException.class, () -> service.canonical(missingNoSuccess, "TC-001"));
        JsonNode notEmpty = negative.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) notEmpty.path("steps").get(0)).put("data", "张三");
        assertThrows(BusinessException.class, () -> service.canonical(notEmpty, "TC-001"));
        JsonNode genericBlank = negative.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) genericBlank).remove("uiCoverage");
        assertThrows(BusinessException.class, () -> service.canonical(genericBlank, "TC-001"));
    }

    @Test
    void selfHealCanChangeOnlyStepLocatorsNotDataOrAssertions() throws Exception {
        JsonNode original = mapping("请添加访客", "${phone13}", "邀请成功");
        JsonNode locatorOnly = mapping("添加访客", "${phone13}", "邀请成功");
        assertTrue(service.sameExceptLocators(original, locatorOnly));
        assertFalse(service.sameExceptLocators(original, mapping("添加访客", "${phone14}", "邀请成功")));
        assertFalse(service.sameExceptLocators(original, mapping("添加访客", "${phone13}", "操作成功")));
        assertFalse(service.sameExceptLocators(original, original));
    }

    @Test
    void healedLocatorKeepsAtMostFourDistinctFallbackBranches() throws Exception {
        JsonNode current = mapping("旧入口", "${phone13}", "邀请成功");
        for (int i = 0; i < 7; i++) {
            String previousPrimary = current.path("steps").get(0).path("locator").path("value").asText();
            JsonNode proposal = mapping("新入口" + i, "${phone13}", "邀请成功");
            ((com.fasterxml.jackson.databind.node.ObjectNode) proposal.path("steps").get(0).path("locator"))
                    .remove("fallback");
            JsonNode merged = service.retainLocatorBranches(current, proposal);
            assertTrue(service.sameExceptLocators(current, merged));
            assertEquals("新入口" + i, merged.path("steps").get(0).path("locator").path("value").asText());
            assertTrue(merged.path("steps").get(0).path("locator").path("fallback").size() <= 4);
            assertEquals(previousPrimary, merged.path("steps").get(0).path("locator").path("fallback").get(0).path("value").asText());
            service.canonical(merged, "TC-001");
            current = merged;
        }
        assertEquals(4, current.path("steps").get(0).path("locator").path("fallback").size());
    }

    @Test
    void proposedFallbackPrecedesOldLocatorBranches() throws Exception {
        JsonNode current = mapping("旧入口", "${phone13}", "邀请成功");
        JsonNode proposal = mapping("新入口", "${phone13}", "邀请成功");
        ((com.fasterxml.jackson.databind.node.ObjectNode) proposal.path("steps").get(0).path("locator")
                .path("fallback").get(0)).put("value", "新备用入口");
        JsonNode branches = service.retainLocatorBranches(current, proposal).path("steps").get(0)
                .path("locator").path("fallback");
        assertEquals("新备用入口", branches.get(0).path("value").asText());
        assertEquals("旧入口", branches.get(1).path("value").asText());
    }

    @Test
    void oldAgentFieldNamesProduceActionableBusinessErrors() throws Exception {
        JsonNode oldShape = mapper.readTree("""
                {"caseNo":"TC-001","page":"/visit/new","steps":[
                  {"action":"click","locator":{"by":"text","value":"新增"}}],
                 "assertions":[{"locator":{"by":"text","value":"成功"},"expect":"成功"}]}
                """);
        BusinessException action = assertThrows(BusinessException.class,
                () -> service.canonical(oldShape, "TC-001"));
        assertTrue(action.getMessage().contains("type"));
        JsonNode badLocator = mapper.readTree("""
                {"caseNo":"TC-001","page":"/visit/new","steps":[
                  {"type":"click","locator":{"value":"新增"}}],
                 "assertions":[{"locator":{"by":"text","value":"成功"}}]}
                """);
        BusinessException locator = assertThrows(BusinessException.class,
                () -> service.canonical(badLocator, "TC-001"));
        assertTrue(locator.getMessage().contains("by"));
    }

    @Test
    void apiMappingSupportsRequestExtractionAndTypedAssertionsWithoutLiteralSecrets() throws Exception {
        JsonNode valid = mapper.readTree("""
                {"caseNo":"TC-001","steps":[
                  {"method":"POST","path":"/login","body":{"password":"${secret:LOGIN_PASS}"},
                   "extract":{"access":"$.data.token"}},
                  {"method":"GET","path":"/items/${id}","headers":{"Authorization":"Bearer ${access}"},
                   "retryCount":1}],
                 "assertions":[
                  {"stepIndex":0,"target":"STATUS","operator":"EQ","expected":200},
                  {"stepIndex":1,"target":"JSON","path":"$.data.id","operator":"EQ","expected":42}]}
                """);
        assertTrue(service.canonical(valid, "TC-001", "API").contains("secret:LOGIN_PASS"));
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-002", "API"));
        assertThrows(BusinessException.class, () -> service.canonical(valid, "TC-001", "UI"));
        JsonNode literal = mapper.readTree("""
                {"caseNo":"TC-001","steps":[{"method":"POST","path":"/login",
                  "headers":{"Authorization":"Bearer abcdefghijklmnop"}}],
                 "assertions":[{"stepIndex":0,"target":"STATUS","operator":"EQ","expected":200}]}
                """);
        assertThrows(BusinessException.class, () -> service.canonical(literal, "TC-001", "API"));
        JsonNode outside = mapper.readTree("""
                {"caseNo":"TC-001","steps":[{"method":"GET","path":"//outside.example/path"}],
                 "assertions":[{"stepIndex":0,"target":"STATUS","operator":"EQ","expected":200}]}
                """);
        assertThrows(BusinessException.class, () -> service.canonical(outside, "TC-001", "API"));
        JsonNode querySecret = mapper.readTree("""
                {"caseNo":"TC-001","steps":[{"method":"GET","path":"/items?access_token=plain-secret"}],
                 "assertions":[{"stepIndex":0,"target":"STATUS","operator":"EQ","expected":200}]}
                """);
        assertThrows(BusinessException.class, () -> service.canonical(querySecret, "TC-001", "API"));
    }

    @Test
    void uiMappingSupportsScopedDrawerActionAndStateWait() throws Exception {
        JsonNode valid = mapper.readTree("""
                {"caseNo":"TC-001","page":"/invite/new","steps":[
                  {"type":"click","locator":{"by":"role","value":"button","name":"提 交",
                    "within":{"by":"css","value":".ant-drawer:visible"}},
                    "waitForResponse":{"path":"/whiteList/batchCheckBlacklist","method":"POST","status":200}},
                  {"type":"waitFor","locator":{"by":"css","value":".ant-drawer"},
                    "state":"hidden","timeout":10000}],
                 "assertions":[{"by":"text","value":"操作成功","expected":"操作成功","match":"equals"}]}
                """);
        assertTrue(service.canonical(valid, "TC-001").contains("waitFor"));
        assertTrue(service.canonical(valid, "TC-001").contains("waitForResponse"));
        assertTrue(service.canonical(valid, "TC-001").contains("\"match\":\"equals\""));
        JsonNode externalResponse = mapper.readTree("""
                {"caseNo":"TC-001","page":"/invite/new","steps":[
                  {"type":"click","locator":{"by":"text","value":"提交"},
                   "waitForResponse":{"path":"//other.example/steal","method":"POST"}}],
                 "assertions":[{"by":"text","value":"操作成功"}]}
                """);
        assertThrows(BusinessException.class, () -> service.canonical(externalResponse, "TC-001"));
        JsonNode nested = mapper.readTree("""
                {"caseNo":"TC-001","page":"/invite/new","steps":[
                  {"type":"click","locator":{"by":"text","value":"提 交",
                    "within":{"by":"css","value":".ant-drawer",
                      "within":{"by":"css","value":"body"}}}}],
                 "assertions":[{"by":"text","value":"操作成功"}]}
                """);
        assertThrows(BusinessException.class, () -> service.canonical(nested, "TC-001"));
    }
    @Test
    void logRuleChangesCannotMasqueradeAsLocatorSelfHealing() throws Exception {
        JsonNode original = mapping("旧入口", "${phone13}", "邀请成功");
        var rule = mapper.createObjectNode().put("id", "request").put("stepIndex", 0)
                .put("purpose", "REQUEST_ASSOCIATION").put("expected", "POST /api/invitations");
        ((com.fasterxml.jackson.databind.node.ObjectNode) original).putArray("logAssertions").add(rule);
        JsonNode changed = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("steps").get(0).path("locator")).put("value", "新入口");
        assertTrue(service.sameExceptLocators(original, changed));
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("logAssertions").get(0)).put("expected", "anything");
        assertFalse(service.sameExceptLocators(original, changed));
    }

}
