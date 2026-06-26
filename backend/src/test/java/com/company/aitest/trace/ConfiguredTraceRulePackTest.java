package com.company.aitest.trace;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ConfiguredTraceRulePack 测试。
 */
class ConfiguredTraceRulePackTest {

    @Test
    void shouldReturnNullWhenCheckboxSemanticRuleDoesNotMatch() {
        ConfiguredTraceRulePack pack = new ConfiguredTraceRulePack(List.of(), List.of(
                new DialogInputConfirmWorkflowProcessor(),
                new EntryNavigationSubmitWorkflowProcessor()
        ));

        var event = new BrowserTraceEventRecord(
                1L, 1L, 1L, 1L,
                "CHANGE", "/test", "配置页面", "未知选项",
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
