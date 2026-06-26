package com.company.aitest.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProjectSemanticContextServiceWikiRecallTest {

    @Test
    void loadWikiSignals_includesReusableScopeFromOtherProjects() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var service = new ProjectSemanticContextService(jdbcTemplate);

        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(10L)))
                .thenReturn(List.of());

        var method = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                ProjectSemanticContextService.class.getDeclaredMethod("loadWikiSignals", Long.class));
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, 10L));
        verify(jdbcTemplate).query(
                contains("project_id = ? OR wp.scope IN ('REUSABLE', 'SYSTEM')"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(10L));
    }

    @Test
    void loadLoopSignals_skipsWhenToggleOff() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var service = new ProjectSemanticContextService(jdbcTemplate);

        when(jdbcTemplate.queryForObject(contains("system_feature_toggle"), eq(Boolean.class)))
                .thenReturn(false);

        var method = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                ProjectSemanticContextService.class.getDeclaredMethod("loadLoopSignals", Long.class));
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var result = (List<?>) assertDoesNotThrow(() -> method.invoke(service, 10L));
        assertTrue(result.isEmpty());
    }

    @Test
    void loadLoopSignals_returnsEmptyOnDbError() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var service = new ProjectSemanticContextService(jdbcTemplate);

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class)))
                .thenThrow(new RuntimeException("db error"));

        var method = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                ProjectSemanticContextService.class.getDeclaredMethod("loadLoopSignals", Long.class));
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var result = (List<?>) assertDoesNotThrow(() -> method.invoke(service, 10L));
        assertTrue(result.isEmpty());
    }
}
