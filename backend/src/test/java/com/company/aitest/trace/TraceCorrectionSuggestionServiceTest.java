package com.company.aitest.trace;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceCorrectionSuggestionServiceTest {

    private final TraceCorrectionSuggestionService service =
            new TraceCorrectionSuggestionService(null, null, null, null, null, null, List.of(new ConfiguredTraceRulePack()));

    @Test
    void extractStepLevelCandidates_shouldSuggestRewriteWhenObjectLabelImprovesListStep() {
        var steps = List.of(
                new TraceStepNormalizer.CleanTraceStep(1, "默认身份", "CLICK", "点击列表中的详情按钮",
                        "我的预约", "/selfbooking", 1_000L)
        );
        var events = List.of(
                new BrowserTraceEventRecord(
                        10L, 1L, 1L, 1L,
                        "CLICK", "/selfbooking", "我的预约", "详情",
                        "button", "#detail", null,
                        null, null, null,
                        null, "user3", null,
                        null, "Asia/Shanghai", 1_050L, null)
        );

        var candidates = service.extractStepLevelCandidates(steps, events);

        assertTrue(candidates.stream().anyMatch(item ->
                item.stepNo() == 1
                        && "REWRITE".equals(item.operationType())
                        && item.candidateStepText() != null
                        && item.candidateStepText().contains("user3")));
    }

    @Test
    void applyStepCorrectionsToCleanSteps_shouldRewriteDropAndMerge() {
        var steps = List.of(
                new TraceStepNormalizer.CleanTraceStep(1, "默认身份", "CLICK", "点击提交按钮",
                        "页面A", "/a", 1_000L),
                new TraceStepNormalizer.CleanTraceStep(2, "默认身份", "CLICK", "点击空白处",
                        "页面A", "/a", 2_000L),
                new TraceStepNormalizer.CleanTraceStep(3, "默认身份", "INPUT", "输入姓名a",
                        "页面A", "/a", 3_000L),
                new TraceStepNormalizer.CleanTraceStep(4, "默认身份", "INPUT", "输入姓名ab",
                        "页面A", "/a", 3_300L)
        );

        var corrections = List.of(
                correction(101L, 1, "REWRITE", "点击提交按钮", "点击“提交”按钮", null),
                correction(102L, 2, "DROP", "点击空白处", null, null),
                correction(103L, 3, "MERGE", "输入姓名a", "输入姓名ab", 4)
        );

        var result = service.applyStepCorrectionsToCleanSteps(steps, corrections);

        assertEquals(2, result.size());
        assertEquals("点击“提交”按钮", result.get(0).description());
        assertEquals("输入姓名ab", result.get(1).description());
    }

    @Test
    void applyConfirmedToCleanSteps_shouldIgnoreStepScopedCorrections() {
        var steps = List.of(
                new TraceStepNormalizer.CleanTraceStep(1, "默认身份", "CLICK", "点击“确定”按钮",
                        "我的预约", "/selfbooking", 1_000L)
        );

        var corrections = List.of(
                correction(201L, 1, "REWRITE", "点击“确定”按钮", "选择时间“20260604 10:15”", null)
        );

        var result = service.applyConfirmedToCleanSteps(steps, corrections);

        assertEquals(1, result.size());
        assertEquals("点击“确定”按钮", result.get(0).description());
    }

    @Test
    void manualStepCandidateValue_shouldKeepNonNullValueForDrop() {
        assertEquals("点击空白处",
                service.manualStepCandidateValue("点击空白处", "DROP", null));
        assertEquals("改写后的步骤",
                service.manualStepCandidateValue("原步骤", "REWRITE", "改写后的步骤"));
    }

    @Test
    void manualStepCandidateStepText_shouldOnlyExistForRewriteLikeOperations() {
        assertEquals("改写后的步骤",
                service.manualStepCandidateStepText("REWRITE", "改写后的步骤"));
        assertEquals(null,
                service.manualStepCandidateStepText("DROP", null));
    }

    @Test
    void isRiskyGenericStepRewrite_shouldDetectOverBroadTemporalRewrite() {
        assertTrue(service.isRiskyGenericStepRewrite(
                "点击“确定”按钮",
                "选择时间“20260604 10:15”"));
        assertFalse(service.isRiskyGenericStepRewrite(
                "点击“新增”按钮",
                "发起新增申请人"));
    }

    @Test
    void applyLearnedPatternsToCleanSteps_shouldIgnoreGenericConfirmToSpecificTimePattern() {
        var steps = List.of(
                new TraceStepNormalizer.CleanTraceStep(1, "默认身份", "CLICK", "点击“确定”按钮",
                        "我的预约", "/vsk/vst/selfBooking/pcOpen", 1_000L)
        );
        var patterns = List.of(
                new TraceCorrectionPatternRecord(
                        1L, 1L, null, "我的预约", null, null, null, null,
                        "STEP_TEXT_OVERRIDE", "REWRITE", "点击“确定”按钮", "选择时间“20260604 10:15”",
                        1, LocalDateTime.now(), "1", LocalDateTime.now(), LocalDateTime.now())
        );

        var result = service.applyLearnedPatternsToCleanStepsForTest(1L, steps, patterns);

        assertEquals(1, result.size());
        assertEquals("点击“确定”按钮", result.get(0).description());
    }

    @Test
    void parseTemplateStepText_shouldStripMarkersForDisplayAndKeepTemplate() {
        var parsed = service.parseTemplateStepText("选择时间“[20260604 10:15]”并输入验证码{24ae}");

        assertEquals("选择时间“20260604 10:15”并输入验证码24ae", parsed.displayText());
        assertEquals("选择时间“{{DATETIME_1}}”并输入验证码{{CODE_1}}", parsed.templateText());
        assertTrue(parsed.hasTemplateMarkers());
    }

    @Test
    void renderTemplateStepText_shouldFillTemplateWhenCurrentStepContainsDynamicValues() {
        var step = new TraceStepNormalizer.CleanTraceStep(
                1, "默认身份", "INPUT", "在“输入验证码”输入24ae",
                "我的预约", "/selfbooking", 1_000L);

        String rendered = service.renderTemplateStepText("在“输入验证码”输入{{CODE_1}}", step);

        assertEquals("在“输入验证码”输入24ae", rendered);
    }

    @Test
    void renderTemplateStepText_shouldSkipWhenCurrentStepCannotProvideDynamicValue() {
        var step = new TraceStepNormalizer.CleanTraceStep(
                1, "默认身份", "CLICK", "点击“确定”按钮",
                "我的预约", "/selfbooking", 1_000L);

        String rendered = service.renderTemplateStepText("选择时间“{{DATETIME_1}}”", step);

        assertNull(rendered);
    }

    @Test
    void inferCheckboxSemantics_shouldOnlyKeepGenericFallbackOutsidePackRules() {
        TraceCorrectionSuggestionService plainService =
                new TraceCorrectionSuggestionService(null, null, null, null, null, null, List.of());

        var remindEvent = new BrowserTraceEventRecord(
                1L, 1L, 1L, 1L,
                "CHANGE", "/test", "任意页面", "提醒",
                "checkbox", "#remind", "已勾选",
                null, null, null,
                null, null, null,
                null, "Asia/Shanghai", 1_000L, null
        );
        var sendEvent = new BrowserTraceEventRecord(
                2L, 1L, 1L, 1L,
                "CHANGE", "/test", "任意页面", "发送更新通知",
                "checkbox", "#notify", "已勾选",
                null, null, null,
                null, null, null,
                null, "Asia/Shanghai", 1_100L, null
        );

        assertNull(plainService.inferCheckboxSemanticsForTest(remindEvent));
        assertEquals("选择\"发送通知\"选项", plainService.inferCheckboxSemanticsForTest(sendEvent));
    }

    @Test
    void applyLearnedPatternsToCleanSteps_shouldFillTemplateFromNeighborEventContext() {
        var steps = List.of(
                new TraceStepNormalizer.CleanTraceStep(1, "默认身份", "CLICK", "点击“选择您的来访时间”按钮",
                        "我的预约", "/vsk/vst/selfBooking/pcOpen", 1_000L),
                new TraceStepNormalizer.CleanTraceStep(2, "默认身份", "CLICK", "点击“确定”按钮",
                        "我的预约", "/vsk/vst/selfBooking/pcOpen", 1_200L)
        );
        var events = List.of(
                new BrowserTraceEventRecord(
                        10L, 1L, 1L, 1L,
                        "CLICK", "/vsk/vst/selfBooking/pcOpen", "我的预约", "20260604 10:15",
                        "button", "#time-value", null,
                        null, null, null,
                        null, null, null,
                        null, "Asia/Shanghai", 1_150L, null),
                new BrowserTraceEventRecord(
                        11L, 1L, 1L, 1L,
                        "CLICK", "/vsk/vst/selfBooking/pcOpen", "我的预约", "确定",
                        "button", "#confirm", null,
                        null, null, null,
                        null, null, null,
                        null, "Asia/Shanghai", 1_200L, null)
        );
        var patterns = List.of(
                new TraceCorrectionPatternRecord(
                        1L, 1L, "我的预约", null, null, null, null, null,
                        "STEP_TEXT_OVERRIDE", "REWRITE", "点击“确定”按钮", "选择时间“{{DATETIME_1}}”",
                        1, LocalDateTime.now(), "1", LocalDateTime.now(), LocalDateTime.now())
        );

        var result = service.applyLearnedPatternsToCleanStepsForTest(1L, steps, patterns, events);

        assertEquals("选择时间“20260604 10:15”", result.get(1).description());
    }

    private TraceCorrectionCandidateRecord correction(
            Long id,
            Integer stepNo,
            String operationType,
            String sourceText,
            String confirmedStepText,
            Integer relatedStepNo) {
        return new TraceCorrectionCandidateRecord(
                id,
                1L,
                1L,
                null,
                null,
                null,
                null,
                switch (operationType) {
                    case "DROP" -> "STEP_NOISE_DECISION";
                    case "MERGE" -> "STEP_MERGE_SUGGESTION";
                    default -> "STEP_TEXT_OVERRIDE";
                },
                sourceText,
                confirmedStepText,
                null,
                "测试",
                "HIGH",
                "CONFIRMED",
                stepNo,
                "STEP",
                operationType,
                sourceText,
                confirmedStepText,
                relatedStepNo,
                null,
                null,
                null,
                null,
                1L,
                1L,
                LocalDateTime.now(),
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
