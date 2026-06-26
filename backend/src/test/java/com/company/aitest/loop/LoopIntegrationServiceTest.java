package com.company.aitest.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LoopIntegrationServiceTest {

    private final LoopService loopService = mock(LoopService.class);
    private final LoopIntegrationService service = new LoopIntegrationService(loopService);
    private final CurrentUser user = new CurrentUser(1L, "admin", null);

    {
        when(loopService.autoCluster(anyLong())).thenReturn(List.of());
    }

    @Test
    void isEnabled_delegatesToLoopService() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        assertTrue(service.isEnabled());

        when(loopService.isLoopEnabled()).thenReturn(false);
        assertFalse(service.isEnabled());
    }

    @Test
    void onGenerationCompleted_doesNothingWhenDisabled() {
        when(loopService.isLoopEnabled()).thenReturn(false);
        service.onGenerationCompleted(1L, "analysis", "points", "drafts", user);
        verify(loopService, never()).recordEvent(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void onGenerationCompleted_recordsWhenEnabled() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        when(loopService.autoCluster(1L)).thenReturn(List.of());
        service.onGenerationCompleted(1L, "analysis", "testPoints", "draftCases", user);
        verify(loopService).recordEvent(eq(1L), eq("GENERATION_QUALITY"), eq("CASE_GENERATION"),
                any(), any(), isNull(), isNull(), eq(user));
        verify(loopService).autoCluster(1L);
    }

    @Test
    void onTomUsageEvaluated_detectsNoSignals() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        when(loopService.autoCluster(1L)).thenReturn(List.of());
        service.onTomUsageEvaluated(1L, "analysis", null, user);
        verify(loopService).recordEvent(eq(1L), eq("TOM_STRATEGY"), eq("ANALYSIS"),
                isNull(), contains("未使用任何 TOM"), isNull(), isNull(), eq(user));
    }

    @Test
    void onTomUsageEvaluated_okWithSignals() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        service.onTomUsageEvaluated(1L, "analysis", "{\"signals\": [1,2,3]}", user);
        verify(loopService).recordEvent(eq(1L), eq("TOM_STRATEGY"), eq("ANALYSIS"),
                any(), eq("TOM 使用正常"), isNull(), isNull(), eq(user));
}

    @Test
    void onTraceSummaryCompleted_detectsEmpty() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        service.onTraceSummaryCompleted(1L, "", "raw", user);
        verify(loopService).recordEvent(eq(1L), eq("TRACE_SUMMARY_QUALITY"), eq("TRACE_SUMMARY"),
                any(), contains("摘要为空"), isNull(), isNull(), eq(user));
    }

    @Test
    void onChineseLocalizationCheck_detectsEnglish() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        service.onChineseLocalizationCheck(1L, "status: pending, action: submit", "ANALYSIS", user);
        verify(loopService).recordEvent(eq(1L), eq("LOCALIZATION_CHECK"), eq("ANALYSIS"),
                any(), contains("英文技术词"), eq("WIKI"), isNull(), eq(user));
    }

    @Test
    void onChineseLocalizationCheck_passesWithChinese() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        service.onChineseLocalizationCheck(1L, "这是一个测试结果，状态已确认", "ANALYSIS", user);
        verify(loopService).recordEvent(eq(1L), eq("LOCALIZATION_CHECK"), eq("ANALYSIS"),
                any(), eq("中文化检查通过"), eq("WIKI"), isNull(), eq(user));
    }

    @Test
    void onGenerationCompleted_shortDraft_flagged() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        service.onGenerationCompleted(1L, "analysis", "points", "short", user);
        verify(loopService).recordEvent(eq(1L), eq("GENERATION_QUALITY"), eq("CASE_GENERATION"),
                any(), contains("内容过短"), isNull(), isNull(), eq(user));
    }

    @Test
    void onTomUsageEvaluated_fewSignals_flagged() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        service.onTomUsageEvaluated(1L, "analysis", "x", user);
        verify(loopService).recordEvent(eq(1L), eq("TOM_STRATEGY"), eq("ANALYSIS"),
                any(), contains("信号过少"), isNull(), isNull(), eq(user));
    }
}
