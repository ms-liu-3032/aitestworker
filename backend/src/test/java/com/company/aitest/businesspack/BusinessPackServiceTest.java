package com.company.aitest.businesspack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

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

class BusinessPackServiceTest {

    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TimeProvider timeProvider;
    @Mock
    private JdbcClient.StatementSpec listSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<BusinessPackService.BusinessPackRecord> listQuery;
    @Mock
    private JdbcClient.StatementSpec getSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<BusinessPackService.BusinessPackRecord> getQuery;

    private BusinessPackService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new BusinessPackService(jdbcClient, jdbcTemplate, timeProvider);
        user = new CurrentUser(1L, "admin", "ADMIN");
        when(timeProvider.now()).thenReturn(LocalDateTime.of(2026, 6, 13, 10, 0));
    }

    @Test
    void listPacks_queriesByProjectAndStatus() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(listSpec);
        when(listSpec.params(anyMap())).thenReturn(listSpec);
        when(listSpec.query(any(RowMapper.class))).thenReturn(listQuery);
        when(listQuery.list()).thenReturn(List.of());

        var result = service.listPacks(1L, "ACTIVE");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getPack_returnsNullForMissing() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of());

        var result = service.getPack(999L);
        assertNull(result);
    }

    @Test
    void activate_throwsForNonDraftStatus() {
        var activePack = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "test", "AUTO_GENERATED", null, 1, "ACTIVE", null,
                null, 0, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(activePack));

        assertThrows(BusinessException.class, () ->
                service.activate(1L, user));
    }

    @Test
    void deactivate_throwsForNonActiveStatus() {
        var draftPack = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "test", "AUTO_GENERATED", null, 1, "DRAFT", null,
                null, 0, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(draftPack));

        assertThrows(BusinessException.class, () ->
                service.deactivate(1L, user));
    }

    @Test
    void createRelation_throwsForSelfReference() {
        assertThrows(BusinessException.class, () ->
                service.createRelation(1L, 1L, "DEPENDS_ON", null, user));
    }

    @Test
    void refreshForProject_handlesNullProjectId() {
        service.refreshForProject(null);
    }

    @Test
    void refreshForProject_handlesEmptyProjectGracefully() {
        when(jdbcClient.sql(anyString())).thenReturn(listSpec);
        when(listSpec.params(anyMap())).thenReturn(listSpec);
        when(listSpec.query(any(RowMapper.class))).thenReturn(listQuery);
        when(listQuery.list()).thenReturn(List.of());

        service.refreshForProject(1L);
    }

    // =====================================================================
    // 绑定关系测试
    // =====================================================================

    @Test
    void createRuleBinding_throwsWhenPackNotFound() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of());

        assertThrows(BusinessException.class, () ->
                service.createRuleBinding(999L, "TRACE_RULE", "test-rule", null, user));
    }

    @Test
    void createScanBinding_throwsWhenPackNotFound() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of());

        assertThrows(BusinessException.class, () ->
                service.createScanBinding(999L, 1L, "/test", "Test Page", user));
    }

    @Test
    void createTomBinding_throwsWhenPackNotFound() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of());

        assertThrows(BusinessException.class, () ->
                service.createTomBinding(999L, 1L, "Test TOM", "PAGE", user));
    }

    @Test
    void createSemanticBinding_throwsWhenPackNotFound() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of());

        assertThrows(BusinessException.class, () ->
                service.createSemanticBinding(999L, 1L, "TOM", "Test Signal", user));
    }

    // =====================================================================
    // 消费记录测试
    // =====================================================================

    @Test
    void recordConsumption_doesNotThrowForMissingPack() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of());

        // Should not throw even if pack doesn't exist
        service.recordConsumption(999L, "SEMANTIC_CONTEXT", "test", 5);
    }

    @Test
    void listRefreshDiagnostics_queriesByProject() {
        var spec = mock(JdbcClient.StatementSpec.class);
        var query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(contains("business_pack_refresh_diagnostic"))).thenReturn(spec);
        when(spec.param(eq("projectId"), eq(1L))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        var diagnostics = service.listRefreshDiagnostics(1L);

        assertTrue(diagnostics.isEmpty());
        verify(jdbcClient).sql(contains("business_pack_refresh_diagnostic"));
    }

    // =====================================================================
    // 生命周期转换测试
    // =====================================================================

    @Test
    void getAvailableTransitions_returnsEmptyForMissingPack() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of());

        var transitions = service.getAvailableTransitions(999L);
        assertTrue(transitions.isEmpty());
    }

    @Test
    void getAvailableTransitions_draftCanActivateOrArchive() {
        var draftPack = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "test", "AUTO_GENERATED", null, 1, "DRAFT", null,
                null, 0, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(draftPack));

        var transitions = service.getAvailableTransitions(1L);
        assertEquals(2, transitions.size());
        assertTrue(transitions.contains("ACTIVE"));
        assertTrue(transitions.contains("ARCHIVED"));
    }

    @Test
    void getAvailableTransitions_activeCanDeactivateOrArchive() {
        var activePack = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "test", "AUTO_GENERATED", null, 1, "ACTIVE", null,
                null, 0, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(activePack));

        var transitions = service.getAvailableTransitions(1L);
        assertEquals(2, transitions.size());
        assertTrue(transitions.contains("INACTIVE"));
        assertTrue(transitions.contains("ARCHIVED"));
    }

    @Test
    void getAvailableTransitions_archivedIsTerminal() {
        var archivedPack = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "test", "AUTO_GENERATED", null, 1, "ARCHIVED", null,
                null, 0, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(archivedPack));

        var transitions = service.getAvailableTransitions(1L);
        assertTrue(transitions.isEmpty());
    }

    @Test
    void getAvailableTransitions_inactiveCanActivateOrArchive() {
        var inactivePack = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "test", "AUTO_GENERATED", null, 1, "INACTIVE", null,
                null, 0, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        when(jdbcClient.sql(contains("business_pack"))).thenReturn(getSpec);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(inactivePack));

        var transitions = service.getAvailableTransitions(1L);
        assertEquals(2, transitions.size());
        assertTrue(transitions.contains("ACTIVE"));
        assertTrue(transitions.contains("ARCHIVED"));
    }

    // =====================================================================
    // 自动推断关系测试
    // =====================================================================

    @Test
    void inferRelations_returnsZeroForSinglePack() {
        // Mock: only one pack exists
        var pack = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "test", "AUTO_GENERATED", null, 1, "DRAFT", null,
                null, 0, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        when(jdbcClient.sql(contains("business_pack"))).thenReturn(listSpec);
        when(listSpec.params(anyMap())).thenReturn(listSpec);
        when(listSpec.query(any(RowMapper.class))).thenReturn(listQuery);
        when(listQuery.list()).thenReturn(List.of(pack));

        // Mock empty items for the pack
        var itemSpec = mock(JdbcClient.StatementSpec.class);
        var itemQuery = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(contains("business_pack_item"))).thenReturn(itemSpec);
        when(itemSpec.param(anyString(), any())).thenReturn(itemSpec);
        when(itemSpec.query(any(RowMapper.class))).thenReturn(itemQuery);
        when(itemQuery.list()).thenReturn(List.of());

        int created = service.inferRelations(1L);
        assertEquals(0, created);
    }

    @Test
    void inferRelations_handlesEmptyProject() {
        when(jdbcClient.sql(contains("business_pack"))).thenReturn(listSpec);
        when(listSpec.params(anyMap())).thenReturn(listSpec);
        when(listSpec.query(any(RowMapper.class))).thenReturn(listQuery);
        when(listQuery.list()).thenReturn(List.of());

        int created = service.inferRelations(1L);
        assertEquals(0, created);
    }

    @Test
    void inferRelations_createsContainsForStrictTomSubset() {
        var packA = new BusinessPackService.BusinessPackRecord(
                1L, 1L, "订单业务包", "AUTO_GENERATED", "订单", 1, "ACTIVE", null,
                null, 1, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());
        var packB = new BusinessPackService.BusinessPackRecord(
                2L, 1L, "交易业务包", "AUTO_GENERATED", "交易", 1, "ACTIVE", null,
                null, 2, null, null, null, 1L,
                LocalDateTime.now(), LocalDateTime.now());

        var packSpec = mock(JdbcClient.StatementSpec.class);
        var packQuery = mock(JdbcClient.MappedQuerySpec.class);
        when(packSpec.params(anyMap())).thenReturn(packSpec);
        when(packSpec.query(any(RowMapper.class))).thenReturn(packQuery);
        when(packQuery.list()).thenReturn(List.of(packA, packB));

        Queue<JdbcClient.StatementSpec> itemSpecs = new ArrayDeque<>();
        itemSpecs.add(stringListSpec(List.of("下单")));
        itemSpecs.add(stringListSpec(List.of()));
        itemSpecs.add(stringListSpec(List.of("下单", "支付")));
        itemSpecs.add(stringListSpec(List.of()));

        var getSpec = mock(JdbcClient.StatementSpec.class);
        var getQuery = mock(JdbcClient.MappedQuerySpec.class);
        when(getSpec.param(anyString(), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(packA), List.of(packB));

        when(jdbcClient.sql(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("business_pack_item")) {
                return itemSpecs.remove();
            }
            if (sql.contains("WHERE id = :id")) {
                return getSpec;
            }
            return packSpec;
        });

        int created = service.inferRelations(1L);

        assertEquals(2, created);
        verify(jdbcTemplate).update(
                contains("INSERT INTO business_pack_relation"),
                eq(1L), eq(2L), eq(1L), eq("CONTAINS"),
                eq(0.7), contains("包含 1 个 TOM"), any(), any());
    }

    private JdbcClient.StatementSpec stringListSpec(List<String> values) {
        var spec = mock(JdbcClient.StatementSpec.class);
        var query = mock(JdbcClient.MappedQuerySpec.class);
        when(spec.param(any())).thenReturn(spec);
        when(spec.query(eq(String.class))).thenReturn(query);
        when(query.list()).thenReturn(values);
        return spec;
    }

    // =====================================================================
    // 聚类测试
    // =====================================================================

    @Test
    void extractRoutePrefix_handlesNull() throws Exception {
        var method = BusinessPackService.class.getDeclaredMethod("extractRoutePrefix", String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(service, (String) null);
        assertEquals("root", result);
    }

    @Test
    void extractRoutePrefix_handlesEmpty() throws Exception {
        var method = BusinessPackService.class.getDeclaredMethod("extractRoutePrefix", String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(service, "");
        assertEquals("root", result);
    }

    @Test
    void extractRoutePrefix_extractsFirstSegment() throws Exception {
        var method = BusinessPackService.class.getDeclaredMethod("extractRoutePrefix", String.class);
        method.setAccessible(true);
        assertEquals("approval", method.invoke(service, "/approval/list"));
        assertEquals("meeting", method.invoke(service, "/api/meeting/detail"));
        assertEquals("users", method.invoke(service, "/admin/users"));
        assertEquals("crm", method.invoke(service, "/web/crm/customer"));
        assertEquals("orders", method.invoke(service, "/app/orders/list"));
    }

    @Test
    void clusterByPageRoutes_exists() throws NoSuchMethodException {
        var method = BusinessPackService.class.getDeclaredMethod("clusterByPageRoutes", List.class);
        assertNotNull(method);
    }
}
