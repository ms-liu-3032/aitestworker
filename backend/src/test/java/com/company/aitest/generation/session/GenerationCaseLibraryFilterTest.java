package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GenerationCaseLibraryFilterTest {

    @Test
    void localCaseFiltersAreAppliedBeforePagination() {
        var service = new GenerationCaseLibraryService(null, null, null);
        Map<String, Object> params = new HashMap<>();

        String sql = service.appendLocalCaseFilters(new StringBuilder(" WHERE 1=1"), params,
                "审批", List.of("预约"), List.of("P0", "P1"),
                List.of("CONFIRMED"), List.of("GENERATION"), List.of("NEGATIVE")).toString();

        assertTrue(sql.contains("case_title LIKE :keyword"));
        assertTrue(sql.contains("module_name IN (:modules)"));
        assertTrue(sql.contains("priority IN (:priorities)"));
        assertTrue(sql.contains("case_status IN (:statuses)"));
        assertTrue(sql.contains("source_type IN (:sources)"));
        assertTrue(sql.contains("scenario_type IN (:scenarioTypes)"));
        assertEquals("%审批%", params.get("keyword"));
        assertEquals(List.of("预约"), params.get("modules"));
    }

    @Test
    void pageSqlSeparatesFilterFromOrderBy() {
        var service = new GenerationCaseLibraryService(null, null, null);

        String sql = service.buildLocalCasePageSql("(SELECT 1)", " WHERE 1=1");

        assertTrue(sql.contains("WHERE 1=1\nORDER BY"));
    }
}
