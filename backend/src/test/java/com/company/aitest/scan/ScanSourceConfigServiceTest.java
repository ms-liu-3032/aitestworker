package com.company.aitest.scan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class ScanSourceConfigServiceTest {
    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TimeProvider timeProvider;
    @Mock
    private JdbcClient.StatementSpec statementSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<ScanSourceConfigService.ScanSourceConfigRecord> sourceQuery;

    private ScanSourceConfigService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ScanSourceConfigService(jdbcClient, jdbcTemplate, timeProvider);
        user = new CurrentUser(1L, "admin", "ADMIN");
        when(timeProvider.now()).thenReturn(LocalDateTime.of(2026, 6, 15, 10, 0));
    }

    @Test
    void serviceClassExists() {
        assertNotNull(ScanSourceConfigService.class);
    }

    @Test
    void controllerClassExists() {
        assertNotNull(ScanSourceConfigController.class);
    }

    @Test
    void recordClassExists() {
        assertNotNull(ScanSourceConfigService.ScanSourceConfigRecord.class);
    }

    @Test
    void createCommandClassExists() {
        assertNotNull(ScanSourceConfigService.CreateSourceCommand.class);
    }

    @Test
    void updateCommandClassExists() {
        assertNotNull(ScanSourceConfigService.UpdateSourceCommand.class);
    }

    @Test
    void builtinSourceDefinitionKeepsConfigMetadata() {
        var definition = new BuiltinScanSourceDefinition(
                "CRM_HOME",
                "CRM 首页",
                null,
                true,
                "URL_LIST",
                "https://example.com/crm");

        assertEquals("URL_LIST", definition.sourceType());
        assertEquals("https://example.com/crm", definition.sourceUrl());
        assertNull(definition.path());
        assertTrue(definition.defaultSelected());
    }

    @Test
    void updateSource_allowsCurrentProjectSource() {
        mockGetSource(projectSource(10L, true));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        var result = service.updateSource(10L, 1L, new ScanSourceConfigService.UpdateSourceCommand(
                "CRM 首页", "URL_LIST", "https://example.com/crm", null,
                true, true, "项目扫描源", null), user);

        assertEquals(10L, result.projectId());
        verify(jdbcTemplate).update(contains("UPDATE scan_source_config SET"), any(Object[].class));
    }

    @Test
    void updateSource_rejectsGlobalSourceFromProjectEndpoint() {
        mockGetSource(globalSource());

        assertThrows(BusinessException.class, () -> service.updateSource(10L, 1L,
                new ScanSourceConfigService.UpdateSourceCommand("全局", null, null, null,
                        null, null, null, null), user));

        verify(jdbcTemplate, never()).update(contains("UPDATE scan_source_config SET"), any(Object[].class));
    }

    @Test
    void enableSource_rejectsOtherProjectSource() {
        mockGetSource(projectSource(99L, false));

        assertThrows(BusinessException.class, () -> service.enableSource(10L, 1L));

        verify(jdbcTemplate, never()).update(startsWith("UPDATE scan_source_config SET enabled"), any(Object[].class));
    }

    @Test
    void deleteSource_rejectsGlobalSourceFromProjectEndpoint() {
        mockGetSource(globalSource());

        assertThrows(BusinessException.class, () -> service.deleteSource(10L, 1L));

        verify(jdbcTemplate, never()).update(startsWith("DELETE FROM scan_source_config"), any(Object[].class));
    }

    @Test
    void listSources_keepsDisabledProjectSourcesForManagement() {
        var disabledProjectSource = projectSource(10L, false);
        var global = globalSource();
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(sourceQuery);
        when(sourceQuery.list()).thenReturn(List.of(disabledProjectSource), List.of(global));

        var result = service.listSources(10L);

        assertEquals(2, result.size());
        assertFalse(result.get(0).enabled());
        assertNull(result.get(1).projectId());
    }

    private void mockGetSource(ScanSourceConfigService.ScanSourceConfigRecord source) {
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(eq("id"), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(sourceQuery);
        when(sourceQuery.list()).thenReturn(List.of(source), List.of(source));
    }

    private ScanSourceConfigService.ScanSourceConfigRecord projectSource(Long projectId, boolean enabled) {
        return new ScanSourceConfigService.ScanSourceConfigRecord(
                1L, projectId, "CRM_HOME", "CRM 首页", "URL_LIST",
                "https://example.com/crm", null, true, enabled,
                "项目扫描源", null, 1L, LocalDateTime.now(), LocalDateTime.now());
    }

    private ScanSourceConfigService.ScanSourceConfigRecord globalSource() {
        return new ScanSourceConfigService.ScanSourceConfigRecord(
                1L, null, "GLOBAL_HOME", "全局首页", "URL_LIST",
                "https://example.com/global", null, true, true,
                "全局扫描源", null, 1L, LocalDateTime.now(), LocalDateTime.now());
    }
}
