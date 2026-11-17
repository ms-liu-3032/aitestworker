package com.company.aitest.dsh;

import com.company.aitest.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DshMethodologyPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requiresBoundaryMethodsCoverageAndTraceableCaseDesign() throws Exception {
        var points = mapper.readTree("""
                {"points":[{"id":"TP1","title":"输入长度边界","pointType":"BOUNDARY",
                "scopeDecision":"GENERATE","sourceBasis":["需求原文"]}]}
                """);
        var plan = mapper.readTree("""
                {"items":[{"pointId":"TP1","designMethods":["等价类划分法","边界值分析法"],
                "coverageRequirements":["VALID_EQUIVALENCE_CLASS","INVALID_EQUIVALENCE_CLASS",
                "AT_BOUNDARY","JUST_INSIDE_BOUNDARY","JUST_OUTSIDE_BOUNDARY"]}]}
                """);
        var cases = mapper.readTree("""
                {"cases":[{"caseTitle":"验证输入长度边界","moduleName":"/邀请记录/新增","steps":"1. 在输入框输入长度边界值\\n2. 提交表单查看校验结果",
                "expectedResult":"1. 输入框保留完整边界值\\n2. 页面显示该边界值对应的校验结果","caseStrategy":"NODE_FOCUSED",
                "scenarioType":"BOUNDARY","sourceTestPointRefs":["TP1"],
                "designMethods":["等价类划分法","边界值分析法"],
                "designCoverage":["VALID_EQUIVALENCE_CLASS","INVALID_EQUIVALENCE_CLASS",
                "AT_BOUNDARY","JUST_INSIDE_BOUNDARY","JUST_OUTSIDE_BOUNDARY"],
                "sourceBasis":["需求原文"],"unsupportedItems":[],"confidence":0.8}]}
                """);
        assertDoesNotThrow(() -> DshMethodologyPolicy.validatePoints(points));
        assertDoesNotThrow(() -> DshMethodologyPolicy.validateCoverage(plan, points));
        assertDoesNotThrow(() -> DshMethodologyPolicy.validateCases(cases, points, plan));
        assertDoesNotThrow(() -> DshMethodologyPolicy.validateCasesForPoints(cases, points, plan, Set.of("TP1")));
        assertThrows(BusinessException.class,
                () -> DshMethodologyPolicy.validateCasesForPoints(cases, points, plan, Set.of("TP2")));
        assertTrue(DshMethodologyPolicy.matchesDraft(cases, "验证输入长度边界", "/邀请记录/新增", "",
                "1. 在输入框输入长度边界值\n2. 提交表单查看校验结果",
                "1. 输入框保留完整边界值\n2. 页面显示该边界值对应的校验结果"));
        assertFalse(DshMethodologyPolicy.matchesDraft(cases, "另一个用例", "/邀请记录/新增", "",
                "1. 在输入框输入长度边界值\n2. 提交表单查看校验结果",
                "1. 输入框保留完整边界值\n2. 页面显示该边界值对应的校验结果"));

        var missing = mapper.readTree("""
                {"cases":[{"caseTitle":"只测正向","moduleName":"/邀请记录/新增","steps":"1. 在输入框输入有效长度值\\n2. 提交表单查看保存状态",
                "expectedResult":"1. 输入框保留有效长度值\\n2. 页面显示该记录保存完成","caseStrategy":"NODE_FOCUSED",
                "scenarioType":"POSITIVE","sourceTestPointRefs":["TP1"],
                "designMethods":["等价类划分法"],"designCoverage":["VALID_EQUIVALENCE_CLASS"],
                "sourceBasis":["需求原文"],"unsupportedItems":[],"confidence":0.8}]}
                """);
        assertThrows(BusinessException.class, () -> DshMethodologyPolicy.validateCases(missing, points, plan));
        var mismatchedSteps = mapper.readTree("""
                {"cases":[{"caseTitle":"步骤不齐","moduleName":"/邀请记录/新增","steps":"1. 在输入框输入长度边界值\\n2. 提交表单查看校验结果",
                "expectedResult":"1. 页面显示该记录保存完成","caseStrategy":"NODE_FOCUSED",
                "scenarioType":"BOUNDARY","sourceTestPointRefs":["TP1"],
                "designMethods":["等价类划分法","边界值分析法"],
                "designCoverage":["VALID_EQUIVALENCE_CLASS","INVALID_EQUIVALENCE_CLASS",
                "AT_BOUNDARY","JUST_INSIDE_BOUNDARY","JUST_OUTSIDE_BOUNDARY"],
                "sourceBasis":["需求原文"],"unsupportedItems":[],"confidence":0.8}]}
                """);
        assertThrows(BusinessException.class, () -> DshMethodologyPolicy.validateCases(mismatchedSteps, points, plan));

        var atomic = mapper.readTree("""
                {"cases":[{"caseTitle":"邀请记录点击新增可选择新建申请", "moduleName":"/邀请记录/新增",
                "steps":"1. 邀请记录页面点击新增查看选项",
                "expectedResult":"1. 弹窗显示新建申请和生成二维码，默认选中新建申请",
                "caseStrategy":"NODE_FOCUSED","scenarioType":"BOUNDARY","sourceTestPointRefs":["TP1"],
                "designMethods":["等价类划分法","边界值分析法"],
                "designCoverage":["VALID_EQUIVALENCE_CLASS","INVALID_EQUIVALENCE_CLASS",
                "AT_BOUNDARY","JUST_INSIDE_BOUNDARY","JUST_OUTSIDE_BOUNDARY"],
                "sourceBasis":["需求原文"],"unsupportedItems":[],"confidence":0.8}]}
                """);
        assertDoesNotThrow(() -> DshMethodologyPolicy.validateCases(atomic, points, plan));
        var boilerplate = mapper.readTree(atomic.toString().replace("\"moduleName\":\"/邀请记录/新增\"",
                "\"moduleName\":\"/邀请记录/新增\",\"precondition\":\"需求与澄清已确认，测试环境可访问\""));
        assertThrows(BusinessException.class, () -> DshMethodologyPolicy.validateCases(boilerplate, points, plan));
        var dependsOnPreviousCase = mapper.readTree(atomic.toString().replace(
                "邀请记录页面点击新增查看选项", "先执行上一条用例，再在邀请记录页面点击新增查看选项"));
        assertThrows(BusinessException.class,
                () -> DshMethodologyPolicy.validateCases(dependsOnPreviousCase, points, plan));
        var explicitSetup = mapper.readTree(atomic.toString().replace(
                "\"moduleName\":\"/邀请记录/新增\"",
                "\"moduleName\":\"/邀请记录/新增\",\"precondition\":\"已有可独立准备的待审核邀请记录，编号A01\""));
        assertDoesNotThrow(() -> DshMethodologyPolicy.validateCases(explicitSetup, points, plan));
        assertFalse(DshMethodologyPolicy.matchesDraft(atomic, "邀请记录点击新增可选择新建申请",
                "/邀请记录/其他", "", "1. 邀请记录页面点击新增查看选项",
                "1. 弹窗显示新建申请和生成二维码，默认选中新建申请"));
    }
}
