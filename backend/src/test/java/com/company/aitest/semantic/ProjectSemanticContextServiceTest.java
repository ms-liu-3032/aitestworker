package com.company.aitest.semantic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectSemanticContextServiceTest {

    private final ProjectSemanticContextService service = new ProjectSemanticContextService(null);

    @Test
    void extractKeywords_shouldKeepBusinessTermsAndDropGenericNoise() {
        Set<String> keywords = service.extractKeywords("优化申请撤回流程，点击页面按钮后提交审批");

        assertTrue(keywords.contains("优化申请撤回流程"));
        assertTrue(keywords.contains("点击页面按钮后提交审批"));
        assertFalse(keywords.contains("页面"));
        assertFalse(keywords.contains("按钮"));
    }

    @Test
    void rankSignals_shouldPreferRouteMatchedPageSignals() {
        var matchedPage = new ProjectSemanticContextService.SemanticSignal(
                "页面画像", "自助服务", "字段：预约时间、离开时间", "/selfbooking", 1.0, LocalDateTime.now());
        var unmatchedTom = new ProjectSemanticContextService.SemanticSignal(
                "TOM", "审批记录", "类型：PAGE；审批记录页", null, 1.2, LocalDateTime.now());

        List<ProjectSemanticContextService.SemanticSignal> ranked = service.rankSignals(
                List.of(unmatchedTom, matchedPage),
                Set.of("预约时间"),
                Set.of("/selfbooking"),
                5);

        assertEquals("页面画像", ranked.get(0).category());
        assertEquals("自助服务", ranked.get(0).title());
    }

    @Test
    void buildPromptSection_shouldRenderSemanticPackBlock() {
        String prompt = service.buildPromptSection(List.of(
                new ProjectSemanticContextService.SemanticSignal(
                        "步骤模板", "操作：REWRITE", "原始：点击确定；采用：选择时间“{{visit_time}}”",
                        null, 1.0, LocalDateTime.now())
        ));

        assertTrue(prompt.contains("【项目语义包（自动沉淀）】"));
        assertTrue(prompt.contains("步骤模板"));
        assertTrue(prompt.contains("选择时间“{{visit_time}}”"));
    }

    @Test
    void rankSignals_shouldKeepTermSignalsWhenKeywordsHit() {
        var term = new ProjectSemanticContextService.SemanticSignal(
                "术语", "审批流", "高频术语，命中次数：3", null, 0.8, null);
        var other = new ProjectSemanticContextService.SemanticSignal(
                "TOM", "预约记录", "类型：PAGE", null, 1.2, null);

        List<ProjectSemanticContextService.SemanticSignal> ranked = service.rankSignals(
                List.of(other, term),
                Set.of("审批流"),
                Set.of(),
                5);

        assertEquals("术语", ranked.get(0).category());
        assertEquals("审批流", ranked.get(0).title());
    }

    @Test
    void refreshSnapshot_shouldReturnEmptyWhenProjectIsNull() {
        ProjectSemanticContextService.SnapshotRefreshResult result = service.refreshSnapshot(null);

        assertNull(result.packId());
        assertEquals(0, result.signalCount());
        assertEquals(0, result.termCount());
    }

    @SuppressWarnings("unchecked")
    @Test
    void refreshSnapshot_shouldPersistEmptySnapshotWithoutFailing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ProjectSemanticContextService snapshotService = new ProjectSemanticContextService(jdbcTemplate);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyLong(), anyLong())).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyLong())).thenReturn(List.of());
        doNothing().when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), anyLong());
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyLong())).thenReturn(7L);

        ProjectSemanticContextService.SnapshotRefreshResult result = snapshotService.refreshSnapshot(9L);

        assertEquals(7L, result.packId());
        assertEquals(0, result.signalCount());
        assertEquals(0, result.termCount());
        verify(jdbcTemplate).update(anyString(), eq(9L), eq(0), eq(0), anyString(), any(), any(), any());
        verify(jdbcTemplate).update("delete from semantic_pack_item where pack_id = ?", 7L);
    }
}
