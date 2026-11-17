package com.company.aitest.dsh;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.minitom.TestObjectModelRecord;
import com.company.aitest.wiki.WikiService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DshDesignContextServiceTest {
    private final CurrentUser user = new CurrentUser(9L, "tester", "USER");

    @Test
    void directModeKeepsWikiButNeverLoadsTom() {
        var miniTom = mock(MiniTomService.class);
        var wiki = mock(WikiService.class);
        var jdbc = mock(JdbcClient.class);
        var formalSpec = mock(JdbcClient.StatementSpec.class);
        var formalRows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("FROM test_case_asset"))).thenReturn(formalSpec);
        when(formalSpec.param(eq("projectId"), any())).thenReturn(formalSpec);
        when(formalSpec.param(eq("userId"), any())).thenReturn(formalSpec);
        when(formalSpec.param(eq("pattern"), any())).thenReturn(formalSpec);
        when(formalSpec.query(any(RowMapper.class))).thenReturn(formalRows);
        when(formalRows.list()).thenReturn(List.of());
        when(wiki.activeContext(eq(2L), anyString(), eq(8)))
                .thenReturn(Map.of("availableByScope", Map.of("PROJECT", 1)));
        var result = new DshDesignContextService(miniTom, wiki, jdbc)
                .build(2L, "访客审批状态", "DIRECT", user);
        assertEquals(0, result.get("tomAvailableCount"));
        assertEquals(List.of(), result.get("tomRelations"));
        verifyNoInteractions(miniTom);
        verify(jdbc, never()).sql(contains("FROM test_object_model_relation"));
        verify(jdbc, never()).sql(contains("FROM wiki_entry_relation"));
    }

    @Test
    void projectModePreservesConfirmedRelationAndLinkedReviewedWiki() {
        var miniTom = mock(MiniTomService.class);
        var wiki = mock(WikiService.class);
        var jdbc = mock(JdbcClient.class);
        var role = node(11L, "PROJECT", "ROLE", "访客审批人");
        var flow = node(12L, "PROJECT", "FLOW", "审批通过流程");
        var system = node(99L, "SYSTEM", "STATE", "审批通过状态");
        when(miniTom.listActive(2L, null, user)).thenReturn(List.of(role, flow, system));
        var relationSpec = mock(JdbcClient.StatementSpec.class);
        var relationCountSpec = mock(JdbcClient.StatementSpec.class);
        var relationCountRows = mock(JdbcClient.MappedQuerySpec.class);
        var wikiSpec = mock(JdbcClient.StatementSpec.class);
        var formalSpec = mock(JdbcClient.StatementSpec.class);
        var relationRows = mock(JdbcClient.MappedQuerySpec.class);
        var wikiRows = mock(JdbcClient.MappedQuerySpec.class);
        var formalRows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("FROM test_object_model_relation"))).thenReturn(relationSpec);
        when(jdbc.sql(startsWith("SELECT COUNT(*) FROM test_object_model_relation"))).thenReturn(relationCountSpec);
        when(relationCountSpec.param(eq("ids"), any())).thenReturn(relationCountSpec);
        when(relationCountSpec.query(Long.class)).thenReturn(relationCountRows);
        when(relationCountRows.single()).thenReturn(1L);
        when(jdbc.sql(contains("FROM wiki_entry_relation"))).thenReturn(wikiSpec);
        when(jdbc.sql(contains("FROM test_case_asset"))).thenReturn(formalSpec);
        when(relationSpec.param(eq("ids"), any())).thenReturn(relationSpec);
        when(wikiSpec.param(eq("ids"), any())).thenReturn(wikiSpec);
        when(wikiSpec.param("projectId", 2L)).thenReturn(wikiSpec);
        when(formalSpec.param(eq("projectId"), any())).thenReturn(formalSpec);
        when(formalSpec.param(eq("userId"), any())).thenReturn(formalSpec);
        when(formalSpec.param(eq("pattern"), any())).thenReturn(formalSpec);
        when(relationSpec.query(any(RowMapper.class))).thenReturn(relationRows);
        when(wikiSpec.query(any(RowMapper.class))).thenReturn(wikiRows);
        when(formalSpec.query(any(RowMapper.class))).thenReturn(formalRows);
        when(relationRows.list()).thenReturn(List.of(Map.of("id", 7L, "fromTomId", 11L,
                "toTomId", 12L, "relationType", "ROLE_CAN_ACTION", "status", "CONFIRMED")));
        when(wikiRows.list()).thenReturn(List.of(Map.of("entryId", 5L, "relatedTomId", 12L,
                "content", "审批通过后进入待到访状态")));
        when(formalRows.list()).thenReturn(List.of(Map.of("id", 8L, "steps", "1. 审批访客", "expectedResult", "1. 显示待到访")));
        when(wiki.activeContext(eq(2L), anyString(), eq(8)))
                .thenReturn(Map.of("availableByScope", Map.of("PROJECT", 1)));

        var result = new DshDesignContextService(miniTom, wiki, jdbc)
                .build(2L, "访客审批", "PROJECT_TOM", user);
        assertEquals(2, result.get("tomAvailableCount"));
        assertEquals(2, ((List<?>) result.get("tomNodes")).size());
        assertEquals(1, ((List<?>) result.get("tomRelations")).size());
        assertEquals(1L, result.get("tomConfirmedRelationAvailableCount"));
        assertEquals(1, ((List<?>) result.get("wikiLinked")).size());
        assertEquals(List.of(12L), ((Map<?, ?>) ((List<?>) result.get("wikiLinked")).get(0)).get("relatedTomIds"));
        assertEquals(1, ((List<?>) result.get("formalCases")).size());
        verify(jdbc).sql(argThat((String sql) -> sql.contains("wp.status = 'ACTIVE'")
                && sql.contains("we.review_status = 'APPROVED'")
                && sql.contains("we.effective_status = 'ACTIVE'")));
    }

    @Test
    void chineseTermsIncludeShortSemanticPieces() {
        assertTrue(DshDesignContextService.terms("优化访客审批状态").contains("访客审批状态"));
        assertTrue(DshDesignContextService.terms("优化访客审批状态").contains("访客"));
    }

    @Test
    void wikiQueriesUseSharedBusinessWordsInsteadOfOneWholeTomName() {
        var visitor = node(1L, "SYSTEM", "ACTION", "访客补填操作");
        var invitation = node(2L, "SYSTEM", "FLOW", "访客邀请流程");
        var queries = DshDesignContextService.wikiTerms(
                DshDesignContextService.terms("访客补填与访客邀请"), List.of(visitor, invitation));
        assertTrue(queries.contains("访客"));
        assertFalse(queries.contains("访客补填操作"));
    }

    @Test
    void relevantRoleAndStateAreNotCrowdedOutByActions() {
        var miniTom = mock(MiniTomService.class);
        var wiki = mock(WikiService.class);
        var jdbc = mock(JdbcClient.class);
        var models = new java.util.ArrayList<TestObjectModelRecord>();
        for (long id = 1; id <= 30; id++) models.add(node(id, "SYSTEM", "ACTION", "访客操作" + id));
        models.add(node(31L, "SYSTEM", "ROLE", "访客角色"));
        models.add(node(32L, "SYSTEM", "STATE", "访客状态"));
        when(miniTom.listActive(2L, null, user)).thenReturn(models);
        var relationSpec = mock(JdbcClient.StatementSpec.class);
        var relationCountSpec = mock(JdbcClient.StatementSpec.class);
        var relationCountRows = mock(JdbcClient.MappedQuerySpec.class);
        var wikiSpec = mock(JdbcClient.StatementSpec.class);
        var formalSpec = mock(JdbcClient.StatementSpec.class);
        var emptyRows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("FROM test_object_model_relation"))).thenReturn(relationSpec);
        when(jdbc.sql(startsWith("SELECT COUNT(*) FROM test_object_model_relation"))).thenReturn(relationCountSpec);
        when(relationCountSpec.param(eq("ids"), any())).thenReturn(relationCountSpec);
        when(relationCountSpec.query(Long.class)).thenReturn(relationCountRows);
        when(relationCountRows.single()).thenReturn(0L);
        when(jdbc.sql(contains("FROM wiki_entry_relation"))).thenReturn(wikiSpec);
        when(jdbc.sql(contains("FROM test_case_asset"))).thenReturn(formalSpec);
        for (var spec : List.of(relationSpec, wikiSpec, formalSpec)) {
            when(spec.param(anyString(), any())).thenReturn(spec);
            when(spec.query(any(RowMapper.class))).thenReturn(emptyRows);
        }
        when(emptyRows.list()).thenReturn(List.of());
        when(wiki.activeContext(eq(2L), anyString(), eq(8)))
                .thenReturn(Map.of("availableByScope", Map.of("SYSTEM", 1), "matchedCount", 0, "entries", List.of()));
        var result = new DshDesignContextService(miniTom, wiki, jdbc)
                .build(2L, "访客操作角色状态", "PROJECT_AND_SYSTEM_TOM", user);
        var coverage = (Map<?, ?>) result.get("tomTypeCoverage");
        assertEquals(1, ((Map<?, ?>) coverage.get("ROLE")).get("selected"));
        assertEquals(1, ((Map<?, ?>) coverage.get("STATE")).get("selected"));
        assertEquals(24, ((List<?>) result.get("tomNodes")).size());
        verify(wiki).activeContext(eq(2L), eq("访客"), eq(8));
    }

    private TestObjectModelRecord node(Long id, String scope, String type, String name) {
        var record = mock(TestObjectModelRecord.class);
        when(record.id()).thenReturn(id);
        when(record.scope()).thenReturn(scope);
        when(record.modelType()).thenReturn(type);
        when(record.name()).thenReturn(name);
        return record;
    }
}
