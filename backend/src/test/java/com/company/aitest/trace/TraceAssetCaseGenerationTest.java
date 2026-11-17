package com.company.aitest.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

class TraceAssetCaseGenerationTest {

    @Test
    void legacyArrayOutputStillProducesOneWholeTraceCase() {
        TraceAssetService service = new TraceAssetService(
                null, null, null, null, null, null, null, null, null, null, List.of());
        TraceAssetService.CaseDraftInput fallback = new TraceAssetService.CaseDraftInput(
                "访客预约完整操作路径", "访客预约", "已进入预约页面",
                "1. 【员工】打开访客预约页面\n2. 【员工】填写访客信息\n3. 【员工】提交预约\n4. 【系统】展示预约提交结果",
                "预约信息保存成功并展示提交结果。", "P1");

        TraceAssetService.CaseDraftInput result = service.parseCaseOutput("""
                [
                  {"caseTitle":"填写访客姓名","moduleName":"访客预约","precondition":"已打开页面","steps":"输入姓名","expectedResult":"姓名可输入","priority":"P2"},
                  {"caseTitle":"提交预约","moduleName":"访客预约","precondition":"已填写信息","steps":"点击提交","expectedResult":"提交成功","priority":"P1"}
                ]
                """, 9L, fallback);

        assertEquals("填写访客姓名", result.caseTitle());
        assertEquals(fallback.steps(), result.steps());
        assertFalse(result.steps().equals("输入姓名"));
        assertEquals("P2", result.priority());
    }

    @Test
    void objectOutputAlsoKeepsTheCompleteCleanedTimeline() {
        TraceAssetService service = new TraceAssetService(
                null, null, null, null, null, null, null, null, null, null, List.of());
        TraceAssetService.CaseDraftInput fallback = new TraceAssetService.CaseDraftInput(
                "完整路径", "预约", "起始页已打开", "1. 打开页面\n2. 填写表单\n3. 提交", "提交成功", "P2");

        TraceAssetService.CaseDraftInput result = service.parseCaseOutput("""
                {"caseTitle":"访客预约提交","moduleName":"预约","precondition":"已登录","steps":"1. 填写表单\\n2. 提交","expectedResult":"显示预约成功","priority":"P1"}
                """, 9L, fallback);

        assertEquals("访客预约提交", result.caseTitle());
        assertEquals("1. 打开页面\n2. 填写表单\n3. 提交", result.steps());
        assertEquals("显示预约成功", result.expectedResult());
        assertEquals("P1", result.priority());
    }
}
