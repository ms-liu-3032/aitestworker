package com.company.aitest.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class LoopServiceTest {

    private LoopService createService() {
        return new LoopService(mock(JdbcClient.class), mock(JdbcTemplate.class), mock(com.company.aitest.common.TimeProvider.class));
    }

    @Test
    void loopServiceCanBeInstantiated() {
        assertNotNull(createService());
    }

    @Test
    void loopEventRecord_holdsCorrectFields() {
        var now = LocalDateTime.now();
        var event = new LoopEventRecord(1L, 10L, "GENERATION_QUALITY", "CASE_GENERATION", "input", "issue", null, null, "PENDING", 1L, now, now);
        assertEquals(1L, event.id());
        assertEquals(10L, event.projectId());
        assertEquals("GENERATION_QUALITY", event.eventType());
        assertEquals("CASE_GENERATION", event.sourceStage());
        assertEquals("PENDING", event.status());
    }

    @Test
    void loopClusterRecord_holdsCorrectFields() {
        var now = LocalDateTime.now();
        var cluster = new LoopClusterRecord(1L, 10L, "theme", 5, "action", "WIKI", "PENDING", now, now);
        assertEquals(1L, cluster.id());
        assertEquals(10L, cluster.projectId());
        assertEquals("theme", cluster.theme());
        assertEquals(5, cluster.eventCount());
        assertEquals("WIKI", cluster.targetAssetType());
    }

    @Test
    void isLoopEnabled_queriesToggleTable() {
        var tmpl = mock(JdbcTemplate.class);
        when(tmpl.queryForObject(contains("system_feature_toggle"), eq(Boolean.class))).thenReturn(true);
        var svc = new LoopService(mock(JdbcClient.class), tmpl, mock(com.company.aitest.common.TimeProvider.class));
        assertTrue(svc.isLoopEnabled());
    }

    @Test
    void isLoopEnabled_defaultsToFalse() {
        var tmpl = mock(JdbcTemplate.class);
        when(tmpl.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        var svc = new LoopService(mock(JdbcClient.class), tmpl, mock(com.company.aitest.common.TimeProvider.class));
        assertFalse(svc.isLoopEnabled());
    }

    @Test
    void updateClusterStatus_executesUpdate() {
        var tmpl = mock(JdbcTemplate.class);
        var jdbc = mock(JdbcClient.class);
        var spec = mock(JdbcClient.StatementSpec.class);
        var mapped = mock(JdbcClient.MappedQuerySpec.class);
        var svc = new LoopService(jdbc, tmpl, mock(com.company.aitest.common.TimeProvider.class));

        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(any(Long.class))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of(
                new LoopClusterRecord(1L, 10L, "theme", 5, "action", "WIKI", "APPROVED", LocalDateTime.now(), LocalDateTime.now())
        ));

        var result = svc.updateClusterStatus(1L, "APPROVED");
        assertEquals("APPROVED", result.status());
    }

    @Test
    void updateEventStatus_executesUpdate() {
        var tmpl = mock(JdbcTemplate.class);
        var jdbc = mock(JdbcClient.class);
        var spec = mock(JdbcClient.StatementSpec.class);
        var mapped = mock(JdbcClient.MappedQuerySpec.class);
        var svc = new LoopService(jdbc, tmpl, mock(com.company.aitest.common.TimeProvider.class));

        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(any(Long.class))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of(
                new LoopEventRecord(1L, 10L, "TOM_STRATEGY", "ANALYSIS", "raw", "issue", null, null, "CONSUMED", 1L, LocalDateTime.now(), LocalDateTime.now())
        ));

        var result = svc.updateEventStatus(1L, "CONSUMED");
        assertEquals("CONSUMED", result.status());
    }

    @Test
    void loadLoopHints_returnsEmptyWhenDisabled() {
        var tmpl = mock(JdbcTemplate.class);
        when(tmpl.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        var svc = new LoopService(mock(JdbcClient.class), tmpl, mock(com.company.aitest.common.TimeProvider.class));
        var hints = svc.loadLoopHints(1L);
        assertTrue(hints.isEmpty());
    }
}
