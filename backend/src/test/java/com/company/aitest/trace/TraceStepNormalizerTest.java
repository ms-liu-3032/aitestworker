package com.company.aitest.trace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.company.aitest.scan.ControlledScanService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceStepNormalizerTest {

    private final TraceStepNormalizer normalizer = new TraceStepNormalizer(List.of(new ConfiguredTraceRulePack()));

    @Test
    void shouldNormalizeCommonBusinessSteps() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "PAGE_OPEN", "https://example.com/login", "登录页", null, null, null, 0L),
                event(2L, "INPUT", "https://example.com/login", "登录页", "手机号", "textbox", "138****0000", 100L),
                event(3L, "CLICK", "https://example.com/login", "登录页", "登录", "button", null, 200L),
                event(4L, "NAVIGATION", "https://example.com/home", "首页", null, null, null, 500L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "管理员账号"));

        assertEquals(4, steps.size());
        assertEquals("打开页面“登录页”", steps.get(0).description());
        assertEquals("在“手机号”输入138****0000", steps.get(1).description());
        assertEquals("点击“登录”", steps.get(2).description());
        assertEquals("跳转到页面“首页”", steps.get(3).description());
    }

    @Test
    void shouldSkipNoisyScrollAndDeduplicateAdjacentSteps() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "SCROLL", "https://example.com", "列表页", null, null, null, 0L),
                event(2L, "CLICK", "https://example.com", "列表页", "查询", "button", null, 100L),
                event(3L, "CLICK", "https://example.com", "列表页", "查询", "button", null, 120L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认账号"));

        assertEquals(1, steps.size());
        assertEquals("点击“查询”", steps.get(0).description());
    }

    @Test
    void shouldBuildNetworkObservationsForFailures() {
        List<BrowserTraceNetworkRecord> networks = List.of(
                network(1L, "https://example.com/api/login", "POST", 500, 320L, 1, "timeout"),
                network(2L, "https://example.com/api/profile", "GET", 200, 80L, 0, null)
        );

        List<String> observations = normalizer.buildNetworkObservations(networks);

        assertEquals(1, observations.size());
        assertTrue(observations.get(0).contains("/api/login"));
        assertTrue(observations.get(0).contains("500"));
    }

    @Test
    void shouldMergeContinuousInputIntoFinalValue() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "INPUT", "https://example.com/form", "新建申请", "手机号", "textbox", "1", 100L),
                event(2L, "INPUT", "https://example.com/form", "新建申请", "手机号", "textbox", "12", 200L),
                event(3L, "INPUT", "https://example.com/form", "新建申请", "手机号", "textbox", "1234567", 300L),
                event(4L, "CLICK", "https://example.com/form", "新建申请", "提交", "button", null, 600L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认账号"));

        assertEquals(2, steps.size());
        assertEquals("在“手机号”输入1234567", steps.get(0).description());
    }

    @Test
    void shouldKeepLatestInputValueAcrossLongTypingWindow() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "INPUT", "https://example.com/form", "新建申请", "请输入手机号", "textbox", "187290", 9000L),
                event(2L, "KEYDOWN", "https://example.com/form", "新建申请", "请输入手机号", "keyboard", "1", 11000L),
                event(3L, "INPUT", "https://example.com/form", "新建申请", "请输入手机号", "textbox", "1**********", 13152L),
                event(4L, "CLICK", "https://example.com/form", "新建申请", "提交", "button", null, 15000L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认账号"));

        assertEquals(2, steps.size());
        assertEquals("在“输入手机号”输入1**********", steps.get(0).description());
    }

    @Test
    void shouldStopMergingWhenFieldBlursAndResumeAsNewInput() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "INPUT", "https://example.com/form", "新建申请", "输入申请人姓名", "textbox", "fan", 1000L),
                event(2L, "INPUT", "https://example.com/form", "新建申请", "输入申请人姓名", "textbox", "范雯雯", 7000L),
                event(3L, "BLUR", "https://example.com/form", "新建申请", "输入申请人姓名", "textbox", "范雯雯", 7100L),
                event(4L, "INPUT", "https://example.com/form", "新建申请", "输入申请人姓名", "textbox", "范雯", 10000L),
                event(5L, "CLICK", "https://example.com/form", "新建申请", "提交", "button", null, 12000L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认账号"));

        assertEquals(3, steps.size());
        assertEquals("在“输入申请人姓名”输入范雯雯", steps.get(0).description());
        assertEquals("在“输入申请人姓名”输入范雯", steps.get(1).description());
    }

    @Test
    void shouldMergeSingleCharacterInputSequence() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "INPUT", "https://example.com/form", "新建申请", "姓名", "textbox", "张", 100L),
                event(2L, "INPUT", "https://example.com/form", "新建申请", "姓名", "textbox", "三", 180L),
                event(3L, "CLICK", "https://example.com/form", "新建申请", "保存", "button", null, 500L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认账号"));

        assertEquals(2, steps.size());
        assertEquals("在“姓名”输入张三", steps.get(0).description());
    }

    @Test
    void shouldAttachNearbyNetworkForSubmitAction() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "CLICK", "https://example.com/form", "新建申请", "提交", "button", null, 1000L)
        );
        List<BrowserTraceNetworkRecord> networks = List.of(
                network(1L, "https://example.com/api/invitation/create", "POST", 200, 120L, 0, null, 1200L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, networks, Map.of(10L, "默认账号"));

        assertEquals(1, steps.size());
        assertTrue(steps.get(0).description().contains("触发接口 POST /api/invitation/create"));
    }

    @Test
    void shouldNormalizePromptStyleTargetText() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "INPUT", "https://example.com/form", "新建申请", "新增申请 · 请输入申请人姓名", "textbox", "范雯雯", 1000L)
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认账号"));

        assertEquals(1, steps.size());
        assertEquals("在“新增申请 · 输入申请人姓名”输入范雯雯", steps.get(0).description());
    }

    @Test
    void shouldPreferControlledScanPageHintWhenRuntimeTitleIsTooGeneric() {
        List<BrowserTraceEventRecord> events = List.of(
                event(1L, "PAGE_OPEN", "https://example.com/workflow/newRequest?_p=w", "企业数字化行政", null, null, null, 75L)
        );
        Map<String, ControlledScanService.PageScanHint> hints = Map.of(
                "/workflow/newRequest",
                new ControlledScanService.PageScanHint(
                        "WORKFLOW_REROUTE",
                        "审批业务操作面",
                        "新建申请",
                        "/workflow/newRequest",
                        "审批流 / 新建申请",
                        List.of("新建申请"),
                        List.of("输入申请人姓名"),
                        List.of("提交"),
                        List.of("选择流程")
                )
        );

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, List.of(), Map.of(10L, "默认账号"), hints);

        assertEquals(1, steps.size());
        assertEquals("打开页面“审批流 / 新建申请”", steps.get(0).description());
    }

    @Test
    void shouldPrefixListActionWithObjectLabel() {
        BrowserTraceEventRecord click = new BrowserTraceEventRecord(
                1L, 1L, 2L, 10L, "CLICK", "https://example.com/request", "申请管理",
                "删除", "button", null, null, null,
                "role=button[name=\"删除\"]", null, null, "申请单 A-20260626 草稿",
                LocalDateTime.now(), LocalDateTime.now(), "Asia/Shanghai", 500L, LocalDateTime.now());
        List<TraceStepNormalizer.CleanTraceStep> steps =
                normalizer.normalize(List.of(click), Map.of(10L, "默认账号"));
        assertEquals(1, steps.size());
        assertEquals("在“申请单 A-20260626 草稿”行点击“删除”按钮", steps.get(0).description());
    }

    @Test
    void shouldPrefixDialogClickWithDialogTitle() {
        BrowserTraceEventRecord click = new BrowserTraceEventRecord(
                1L, 1L, 2L, 10L, "CLICK", "https://example.com/request", "申请管理",
                "下一步", "button", null, null, null,
                "role=button[name=\"下一步\"]", null, "新建申请", null,
                LocalDateTime.now(), LocalDateTime.now(), "Asia/Shanghai", 500L, LocalDateTime.now());
        List<TraceStepNormalizer.CleanTraceStep> steps =
                normalizer.normalize(List.of(click), Map.of(10L, "默认账号"));
        assertEquals(1, steps.size());
        assertEquals("在“新建申请”弹窗点击“下一步”按钮", steps.get(0).description());
    }

    @Test
    void normalizedLocatorFillsTargetWhenElementTextMissing() {
        // 模拟 Playwright locator.normalize() 已输出，但 elementText 为空（截屏场景常见）
        List<BrowserTraceEventRecord> events = List.of(
                eventWithLocator(1L, "CLICK", "https://example.com/form", "表单页", null, null, null, 0L,
                        "getByRole('button', { name: '保存' })")
        );
        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认身份"));
        assertEquals(1, steps.size());
        assertTrue(steps.get(0).description().contains("保存"),
                "normalized_locator 中的 name 必须出现在描述里：" + steps.get(0).description());
    }

    @Test
    void normalizedLocatorByLabelMaps() {
        List<BrowserTraceEventRecord> events = List.of(
                eventWithLocator(1L, "CLICK", "https://example.com", "页", null, null, null, 0L,
                        "getByLabel('用户名')")
        );
        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(events, Map.of(10L, "默认身份"));
        assertEquals(1, steps.size());
        assertTrue(steps.get(0).description().contains("用户名"));
    }

    @Test
    private BrowserTraceEventRecord event(Long id, String type, String url, String title, String elementText,
                                          String role, String value, Long relativeMs) {
        return new BrowserTraceEventRecord(id, 1L, 2L, 10L, type, url, title, elementText, role,
                null, value, null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now(),
                "Asia/Shanghai", relativeMs, LocalDateTime.now());
    }

    private BrowserTraceEventRecord eventWithLocator(Long id, String type, String url, String title, String elementText,
                                                     String role, String value, Long relativeMs, String normalizedLocator) {
        return new BrowserTraceEventRecord(id, 1L, 2L, 10L, type, url, title, elementText, role,
                null, value, null, normalizedLocator, null, null, null, LocalDateTime.now(), LocalDateTime.now(),
                "Asia/Shanghai", relativeMs, LocalDateTime.now());
    }

    private BrowserTraceEventRecord eventWithContext(Long id, String type, String url, String title, String elementText,
                                                     String role, String value, Long relativeMs,
                                                     String sectionTitle, String dialogTitle, String objectLabel) {
        return new BrowserTraceEventRecord(id, 1L, 2L, 10L, type, url, title, elementText, role,
                null, value, null, null, sectionTitle, dialogTitle, objectLabel,
                LocalDateTime.now(), LocalDateTime.now(), "Asia/Shanghai", relativeMs, LocalDateTime.now());
    }

    private BrowserTraceNetworkRecord network(Long id, String url, String method, Integer statusCode,
                                              Long durationMs, Integer failed, String errorMessage) {
        return network(id, url, method, statusCode, durationMs, failed, errorMessage, 0L);
    }

    private BrowserTraceNetworkRecord network(Long id, String url, String method, Integer statusCode,
                                              Long durationMs, Integer failed, String errorMessage, Long relativeMs) {
        return new BrowserTraceNetworkRecord(id, 1L, 2L, 10L, url, method, statusCode, durationMs, failed,
                errorMessage, null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now(), "Asia/Shanghai", relativeMs, LocalDateTime.now());
    }
}
