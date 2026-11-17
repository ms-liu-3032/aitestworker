package com.company.aitest.generation.session;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasoningPlanServiceTest {

    @Test
    void usesTheActiveSkillRulesInsteadOfEmbeddingBusinessFacts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TimeProvider time = mock(TimeProvider.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 13, 18, 0));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any())).thenReturn(0);
        when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(91L);
        doReturn(List.of(Map.of(
                "skill_key", "FIELD_BOUNDARY", "version", 3,
                "method_rules_json", "{\"methods\":[\"等价类划分法\"],\"obligations\":[\"有效等价类\",\"无效等价类\"]}",
                "quality_rules_json", "{\"rules\":[\"每个字段的边界必须有可执行预期\"]}")))
                .when(jdbc).queryForList(anyString(), any(Object[].class));
        RequirementAnalysisRecord analysis = new RequirementAnalysisRecord(8L, 4L, 1, 0, "金额字段范围调整", "{}", null,
                "[]", "[]", "[]", "[{\"id\":\"TP1\",\"title\":\"金额输入边界\",\"description\":\"金额格式和范围校验\",\"point_type\":\"BOUNDARY\",\"generation_scope\":\"GENERATE\"}]",
                null, null, null, "TEST_POINT_SCOPE_CONFIRMED", LocalDateTime.now(), LocalDateTime.now());

        ReasoningPlanService.PlanView plan = new ReasoningPlanService(jdbc, time, new ObjectMapper())
                .createOrReplace(2L, 4L, analysis, 6L, new CurrentUser(3L, "tester", "OWNER"));

        assertEquals(91L, plan.id());
        assertEquals(1, plan.version());
        assertEquals(List.of("FIELD_BOUNDARY"), plan.items().get(0).get("skills"));
        assertEquals(Map.of("FIELD_BOUNDARY", 3), plan.items().get(0).get("skill_versions"));
        assertTrue(((List<?>) plan.items().get(0).get("methods")).contains("等价类划分法"));
        assertTrue(!plan.items().get(0).toString().contains("金额字段范围调整"));
    }
}
