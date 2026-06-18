package com.company.aitest.trace;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceWorkflowConditionsTest {

    @Test
    void compositeConditions_shouldSupportAllAnyAndNot() {
        TraceWorkflowRuntime runtime = new TraceWorkflowRuntime(List.of(
                step("CLICK", "点击“提交”按钮", "页面A", 1000L)
        ));
        runtime.setFlag("armed");
        TraceStepNormalizer.CleanTraceStep step = runtime.current();

        TraceWorkflowTransition.Condition<String> condition = TraceWorkflowConditions.all(
                TraceWorkflowConditions.actionType("CLICK"),
                TraceWorkflowConditions.hasFlag("armed"),
                TraceWorkflowConditions.any(
                        TraceWorkflowConditions.descriptionContains(rule -> "提交"),
                        TraceWorkflowConditions.descriptionContains(rule -> "保存")
                ),
                TraceWorkflowConditions.not(TraceWorkflowConditions.pageContains(rule -> "页面B"))
        );

        assertTrue(condition.test(runtime, step, "rule", step.description(), step.pageName()));
        assertFalse(TraceWorkflowConditions.not(condition).test(runtime, step, "rule", step.description(), step.pageName()));
    }

    @Test
    void choiceAndAheadConditions_shouldUseConfiguredDescriptions() {
        TraceWorkflowRuntime runtime = new TraceWorkflowRuntime(List.of(
                step("CLICK", "点击“提交”按钮", "页面A", 1000L),
                step("CLICK", "选择“短信通知”", "页面A", 1100L),
                step("NAVIGATION", "跳转到页面“成功页”", "页面A", 1200L)
        ));
        TraceStepNormalizer.CleanTraceStep step = runtime.current();
        ChoiceRule rule = new ChoiceRule(
                List.of("点击“提交”按钮", "选择“短信通知”"),
                List.of("提交申请", "选择短信通知"),
                List.of("成功页")
        );

        assertTrue(
                TraceWorkflowConditions.descriptionChoiceExists(ChoiceRule::descriptions, ChoiceRule::rewrites)
                        .test(runtime, step, rule, step.description(), step.pageName())
        );
        assertTrue(
                TraceWorkflowConditions.hasAheadNavigationDescriptionContains(
                        1,
                        config -> 3,
                        ignored -> "成功页"
                ).test(runtime, step, rule, step.description(), step.pageName())
        );
    }

    private TraceStepNormalizer.CleanTraceStep step(String actionType, String description, String pageName, long relativeMs) {
        return new TraceStepNormalizer.CleanTraceStep(1, "默认身份", actionType, description, pageName, "/test", relativeMs);
    }

    private record ChoiceRule(List<String> descriptions, List<String> rewrites, List<String> navigationTargets) {
    }
}
