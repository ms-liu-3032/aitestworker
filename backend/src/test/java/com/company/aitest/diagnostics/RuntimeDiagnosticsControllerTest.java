package com.company.aitest.diagnostics;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeDiagnosticsControllerTest {

    private final RuntimeDiagnosticsController controller = new RuntimeDiagnosticsController(null);

    @Test
    void normalizeLimitUsesDefaultAndCapsMax() {
        assertEquals(50, controller.normalizeLimit(null));
        assertEquals(50, controller.normalizeLimit(0));
        assertEquals(10, controller.normalizeLimit(10));
        assertEquals(500, controller.normalizeLimit(500));
        assertEquals(1000, controller.normalizeLimit(5000));
    }

    @Test
    void previewTruncatesLongTextWithoutReturningNull() {
        assertEquals("", controller.preview(null, 5));
        assertEquals("abc", controller.preview("abc", 5));
        assertEquals("abcde...", controller.preview("abcdefghi", 5));
    }

    @Test
    void rootRequestIdStripsProviderAttemptSuffix() {
        assertEquals("", controller.rootRequestId(null));
        assertEquals("req-1", controller.rootRequestId(" req-1 "));
        assertEquals("req-1", controller.rootRequestId("req-1#a2"));
    }

    @Test
    void maskSnapshotMasksCredentialLikeContent() {
        String masked = controller.maskSnapshot("api_key=sk-test-secret-value-123456789 Bearer abcdefghijklmnopqrstuvwxyz");

        assertTrue(masked.contains("[MASKED"));
        assertFalse(masked.contains("sk-test-secret-value"));
        assertFalse(masked.contains("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void buildLlmInvocationReportContainsOnlyPreviewAndSummary() {
        RuntimeDiagnosticsController.LlmInvocationView row = new RuntimeDiagnosticsController.LlmInvocationView(
                1L,
                "req-1",
                2L,
                3L,
                4L,
                "TEST_CASE_GENERATION",
                "CASE_GENERATION",
                5L,
                "OTHER",
                "gpt-test",
                1,
                "FAILED",
                "TIMEOUT",
                "模型调用超时",
                1200L,
                100,
                75,
                20,
                "safe preview only",
                LocalDateTime.of(2026, 7, 1, 10, 0)
        );

        String report = controller.buildLlmInvocationReport(List.of(row), 50);

        assertTrue(report.contains("LLM 运行诊断脱敏报告"));
        assertTrue(report.contains("FAILED: 1"));
        assertTrue(report.contains("TIMEOUT: 1"));
        assertTrue(report.contains("Token 合计：input=100, cached_input=75, uncached_input=25, output=20"));
        assertTrue(report.contains("输入缓存命中率：75.0%"));
        assertTrue(report.contains("safe preview only"));
        assertFalse(report.contains("api_key"));
    }

    @Test
    void buildSecurityEventReportContainsOnlyPreviewAndSummary() {
        RuntimeDiagnosticsController.SecurityEventView row = new RuntimeDiagnosticsController.SecurityEventView(
                2L,
                "PROMPT_INJECTION_SUSPECTED",
                "WARN",
                3L,
                4L,
                5L,
                "req-2",
                "{\"signals\":\"IGNORE_PREVIOUS\"}",
                LocalDateTime.of(2026, 7, 1, 11, 0)
        );

        String report = controller.buildSecurityEventReport(List.of(row), 50);

        assertTrue(report.contains("安全事件脱敏报告"));
        assertTrue(report.contains("WARN: 1"));
        assertTrue(report.contains("PROMPT_INJECTION_SUSPECTED: 1"));
        assertTrue(report.contains("IGNORE_PREVIOUS"));
        assertFalse(report.contains("Bearer "));
        assertFalse(report.contains("api_key"));
    }
}
