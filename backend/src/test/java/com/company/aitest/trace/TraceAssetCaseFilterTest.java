package com.company.aitest.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TraceAssetCaseFilterTest {

    @Test
    void formalCaseFiltersExpandActiveStatusAndTraceSource() {
        var service = new TraceAssetService(null, null, null, null, null, null, null, null, null, null, List.of());
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(" WHERE project_id = :projectId");

        service.appendFormalCaseFilters(sql, params, "门禁", List.of("权限"), List.of("P1"),
                List.of("ACTIVE"), List.of("TRACE"));

        assertTrue(sql.toString().contains("case_title LIKE :keyword"));
        assertTrue(sql.toString().contains("module_name IN (:modules)"));
        assertTrue(sql.toString().contains("source_trace_group_id IS NOT NULL"));
        assertEquals(List.of("ACTIVE", "SUBMITTED"), params.get("statuses"));
        assertEquals("%门禁%", params.get("keyword"));
    }

    @Test
    void formalPageSqlSeparatesWhereAndOrderByWithoutFilters() {
        var service = new TraceAssetService(null, null, null, null, null, null, null, null, null, null, List.of());

        String sql = service.buildFormalCasePageSql(" WHERE project_id = :projectId");

        assertTrue(sql.contains(":projectId\nORDER BY"));
        assertFalse(sql.contains(":projectIdORDER"));
    }

    @Test
    void formalPageSqlSeparatesCompoundFiltersAndOrderBy() {
        var service = new TraceAssetService(null, null, null, null, null, null, null, null, null, null, List.of());
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" WHERE project_id = :projectId");
        service.appendFormalCaseFilters(where, params, "审批", List.of("预约"), List.of("P0"),
                List.of("ACTIVE"), List.of("TRACE"));

        String sql = service.buildFormalCasePageSql(where.toString());

        assertTrue(sql.contains(")\nORDER BY"));
        assertFalse(sql.contains(")ORDER BY"));
    }
}
