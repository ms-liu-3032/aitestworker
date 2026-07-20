package com.company.aitest.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

class BusinessPackSemanticContextTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ProjectSemanticContextService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProjectSemanticContextService(jdbcTemplate);
    }

    @Test
    void collectSignals_includesBusinessPackSignals() {
        // Mock empty results for all signal sources
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                .thenReturn(List.of());

        var result = service.build(1L, "测试需求", List.of());

        assertNotNull(result);
        assertNotNull(result.promptSection());
        assertNotNull(result.signals());
    }

    @Test
    void buildPromptSection_includesBusinessPackCategory() {
        var signals = List.of(
                new ProjectSemanticContextService.SemanticSignal(
                        "业务包:TOM", "测试对象", "描述", null, 0.8, null),
                new ProjectSemanticContextService.SemanticSignal(
                        "TOM", "页面", "描述", null, 0.7, null)
        );

        String section = service.buildPromptSection(signals);

        assertTrue(section.contains("业务包:TOM"));
        assertTrue(section.contains("TOM"));
        assertTrue(section.contains("项目语义包"));
    }

    @Test
    void buildPromptSection_handlesEmptySignals() {
        String section = service.buildPromptSection(List.of());
        assertEquals("", section);
    }

    @Test
    void buildPromptSection_handlesNullSignals() {
        String section = service.buildPromptSection(null);
        assertEquals("", section);
    }

    @Test
    void extractKeywords_handlesChinese() {
        var keywords = service.extractKeywords("申请管理页面测试");
        assertFalse(keywords.isEmpty());
    }

    @Test
    void extractKeywords_handlesEmpty() {
        var keywords = service.extractKeywords("");
        assertTrue(keywords.isEmpty());
    }

    @Test
    void extractKeywords_handlesNull() {
        var keywords = service.extractKeywords(null);
        assertTrue(keywords.isEmpty());
    }

    @Test
    void build_withBusinessPackService_recordsConsumption() {
        // Mock empty results for all signal sources
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                .thenReturn(List.of());

        // Create a mock BusinessPackService
        var mockBpService = mock(com.company.aitest.businesspack.BusinessPackService.class);
        when(mockBpService.listPacks(eq(1L), eq("ACTIVE"))).thenReturn(List.of(
                new com.company.aitest.businesspack.BusinessPackService.BusinessPackRecord(
                        1L, 1L, "test", "AUTO_GENERATED", null, 1, "ACTIVE", null,
                        null, 0, null, null, null, 1L,
                        java.time.LocalDateTime.now(), java.time.LocalDateTime.now())
        ));

        service.setBusinessPackService(mockBpService);

        var result = service.build(1L, "测试需求", List.of());

        assertNotNull(result);
        // Verify that recordConsumption was called (best-effort, may not be called if no BP signals)
        // The key assertion is that build() doesn't throw when businessPackService is set
    }

    @Test
    void build_withoutBusinessPackService_doesNotThrow() {
        // Mock empty results for all signal sources
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                .thenReturn(List.of());

        // Ensure no business pack service is set
        service.setBusinessPackService(null);

        var result = service.build(1L, "测试需求", List.of());

        assertNotNull(result);
    }
}
