package com.company.aitest.trace;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ConfiguredTraceRulePack 测试。
 * <p>
 * 业务规则包（如 visitor-pack.json）已移至 trace-rulepacks-samples/ 目录，
 * 不再参与默认运行态。测试通过显式加载 samples 中的 pack 来验证规则引擎能力。
 */
class ConfiguredTraceRulePackTest {

    @TempDir
    Path tempDir;

    private ConfiguredTraceRulePack loadVisitorPack() throws Exception {
        Path samplePack = Path.of("src/test/resources/trace-rulepacks-samples/visitor-pack.json");
        if (!Files.exists(samplePack)) {
            return new ConfiguredTraceRulePack(List.of(), List.of(
                    new DialogInputConfirmWorkflowProcessor(),
                    new EntryNavigationSubmitWorkflowProcessor()
            ));
        }
        ObjectMapper mapper = new ObjectMapper();
        TraceRuleSetConfig config;
        try (InputStream is = Files.newInputStream(samplePack)) {
            config = mapper.readValue(is, TraceRuleSetConfig.class);
        }
        return new ConfiguredTraceRulePack(
                List.of(config),
                List.of(
                        new DialogInputConfirmWorkflowProcessor(),
                        new EntryNavigationSubmitWorkflowProcessor()
                )
        );
    }

    @Test
    void shouldApplyConfiguredCancelInviteFlowWithoutBusinessJavaClass() throws Exception {
        ConfiguredTraceRulePack pack = loadVisitorPack();

        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“取消test003未开始test”按钮", "邀请记录", 1_000L),
                step("INPUT", "在“输入取消原因”输入临时有事取消", "取消邀请", 1_500L),
                step("CLICK", "确认取消邀请", "取消邀请", 2_000L)
        );

        List<TraceStepNormalizer.CleanTraceStep> result = pack.postProcess(steps);

        assertEquals(3, result.size());
        assertEquals("发起取消邀请", result.get(0).description());
        assertEquals("在“输入取消原因”输入临时有事取消", result.get(1).description());
        assertEquals("保持“不发送短信”状态并确认取消邀请", result.get(2).description());
    }

    @Test
    void shouldApplyConfiguredModifyInviteFlowWithoutBusinessJavaClass() throws Exception {
        ConfiguredTraceRulePack pack = loadVisitorPack();

        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“修改”按钮", "邀请记录", 1_000L),
                step("NAVIGATION", "跳转到页面“新建邀请”", "添加访客", 1_200L),
                step("CLICK", "点击“输入访客手机”", "添加访客", 1_300L),
                step("INPUT", "在“输入访客手机”输入13800000000", "添加访客", 1_500L),
                step("CLICK", "点击“确定”按钮", "添加访客", 1_800L),
                step("CLICK", "点击“提交”按钮", "添加访客", 2_100L),
                step("CLICK", "点击“是”按钮", "添加访客", 2_300L)
        );

        List<TraceStepNormalizer.CleanTraceStep> result = pack.postProcess(steps);

        assertEquals(6, result.size());
        assertEquals("发起邀请变更", result.get(0).description());
        assertEquals("进入邀请变更页面", result.get(1).description());
        assertEquals("在“输入访客手机”输入13800000000", result.get(2).description());
        assertEquals("确认访客信息修改", result.get(3).description());
        assertEquals("提交邀请变更", result.get(4).description());
        assertEquals("选择“发送更新通知”", result.get(5).description());
    }

    @Test
    void shouldSuggestConfiguredCheckboxSemantics() throws Exception {
        ConfiguredTraceRulePack pack = loadVisitorPack();

        var event = new BrowserTraceEventRecord(
                1L, 1L, 1L, 1L,
                "CHANGE", "/test", "取消邀请", "发送短信",
                "checkbox", "#sms", "已勾选",
                null, null, null,
                null, null, null,
                null, "Asia/Shanghai", 1_000L, null
        );

        assertEquals("选择\"发送短信\"选项", pack.suggestCheckboxSemantics(event));
    }

    @Test
    void shouldReturnNullWhenCheckboxSemanticRuleDoesNotMatch() {
        ConfiguredTraceRulePack pack = new ConfiguredTraceRulePack(List.of(), List.of(
                new DialogInputConfirmWorkflowProcessor(),
                new EntryNavigationSubmitWorkflowProcessor()
        ));

        var event = new BrowserTraceEventRecord(
                1L, 1L, 1L, 1L,
                "CHANGE", "/test", "取消邀请", "未知选项",
                "checkbox", "#other", "已勾选",
                null, null, null,
                null, null, null,
                null, "Asia/Shanghai", 1_000L, null
        );

        assertNull(pack.suggestCheckboxSemantics(event));
    }

    @Test
    void shouldLoadActiveDatabaseRulePack() {
        TraceRulePackConfigRepository repository = mock(TraceRulePackConfigRepository.class);
        when(repository.loadActivePacks(null)).thenReturn(List.of(
                new TraceRulePackConfigRepository.RulePackConfig("crm-pack", crmInputRulePack())
        ));
        ConfiguredTraceRulePack pack = new ConfiguredTraceRulePack(repository);

        TraceRulePack.DescriptionDecision decision = pack.describeInput(new TraceDescriptionContext(
                null,
                "客户姓名",
                "张三",
                null,
                null,
                null,
                "客户档案"
        ));

        assertEquals(true, decision.handled());
        assertEquals("填写客户姓名：张三", decision.description());
    }

    @Test
    void normalizerShouldApplyProjectScopedDatabaseRulePack() {
        TraceRulePackConfigRepository repository = mock(TraceRulePackConfigRepository.class);
        when(repository.loadActivePacks(7L)).thenReturn(List.of(
                new TraceRulePackConfigRepository.RulePackConfig("crm-pack", crmInputRulePack())
        ));
        when(repository.loadActivePacks(null)).thenReturn(List.of());
        TraceStepNormalizer normalizer = new TraceStepNormalizer(List.of(new ConfiguredTraceRulePack(repository)));

        List<TraceStepNormalizer.CleanTraceStep> steps = normalizer.normalize(
                7L,
                List.of(event("INPUT", "客户姓名", "张三")),
                Map.of(10L, "默认身份"));

        assertEquals(1, steps.size());
        assertEquals("填写客户姓名：张三", steps.get(0).description());
    }

    private TraceRuleSetConfig crmInputRulePack() {
        return new TraceRuleSetConfig(
                "crm-pack",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new TraceRuleSetConfig.DescribeRule(
                        null,
                        "客户姓名",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "REPLACE",
                        "填写${field}：${value}"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private TraceStepNormalizer.CleanTraceStep step(String actionType, String description, String pageName, long relativeMs) {
        return new TraceStepNormalizer.CleanTraceStep(1, "默认身份", actionType, description, pageName, "/test", relativeMs);
    }

    private BrowserTraceEventRecord event(String type, String text, String value) {
        return new BrowserTraceEventRecord(
                1L, 1L, 1L, 10L,
                type, "/crm/customer", "客户档案", text,
                "input", "#field", value,
                null, null, null,
                null, null, null,
                null, "Asia/Shanghai", 1_000L, null
        );
    }
}
