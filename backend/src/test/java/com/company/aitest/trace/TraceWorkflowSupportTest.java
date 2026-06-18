package com.company.aitest.trace;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceWorkflowSupportTest {

    @Test
    void extractInputValue_shouldPreferConfiguredPrefixAndFallbackWhenMissing() {
        assertEquals(
                "临时有事取消",
                TraceWorkflowSupport.extractInputValue("在“输入取消原因”输入临时有事取消", "输入", "输入内容")
        );
        assertEquals(
                "默认值",
                TraceWorkflowSupport.extractInputValue("点击“确定”按钮", "输入", "默认值")
        );
    }

    @Test
    void selectBestInputValue_shouldFilterPlaceholderAndTrailingNoise() {
        assertEquals(
                "临时有事取消",
                TraceWorkflowSupport.selectBestInputValue(
                        "在“输入取消原因”输入临时有事取消abc",
                        null,
                        "输入",
                        List.of("请输入原因", "输入内容"),
                        "输入内容"
                )
        );
        assertEquals(
                "已有值",
                TraceWorkflowSupport.selectBestInputValue(
                        "在“输入取消原因”输入请输入原因",
                        "已有值",
                        "输入",
                        List.of("请输入原因", "输入内容"),
                        "输入内容"
                )
        );
        assertNull(
                TraceWorkflowSupport.selectBestInputValue(
                        null,
                        null,
                        "输入",
                        List.of("请输入原因"),
                        "输入内容"
                )
        );
    }

    @Test
    void stripSuffixTokens_shouldRemoveConfiguredSuffixWhenPresent() {
        assertEquals(
                "保持“不发送短信”状态并确认取消邀请",
                TraceWorkflowSupport.stripSuffixTokens(
                        "保持“不发送短信”状态并确认取消邀请，触发接口 GET /wx/getServerTime",
                        List.of("，触发接口 GET /wx/getServerTime")
                )
        );
        assertEquals(
                "确认邀请变更",
                TraceWorkflowSupport.stripSuffixTokens("确认邀请变更", List.of("不存在的后缀"))
        );
    }
}
