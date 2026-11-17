package com.company.aitest.dsh;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DshWorkSessionServiceTest {
    private final DshWorkSessionService service = new DshWorkSessionService(mock(JdbcClient.class),
            mock(JdbcTemplate.class), mock(TimeProvider.class), mock(ProjectAccessService.class), new ObjectMapper());

    @Test
    void enforcesStructuredStageContractWithoutInference() {
        assertDoesNotThrow(() -> service.validate("REQUIREMENT_ANALYSIS", "{\"summary\":\"访客审批\"}"));
        assertDoesNotThrow(() -> service.validate("TEST_POINTS", """
                {"points":[{"id":"TP1","title":"审批状态","pointType":"MAIN_FLOW",
                "scopeDecision":"GENERATE","sourceBasis":["需求原文"]}]}
                """));
        assertThrows(BusinessException.class, () -> service.validate("TEST_POINTS",
                "{\"points\":[{\"title\":\"审批状态\"}]}"));
        assertThrows(BusinessException.class, () -> service.validate("TEST_POINTS", "{\"points\":\"文本\"}"));
        assertThrows(BusinessException.class, () -> service.validate("TEST_POINTS", "{\"points\":[]}"));
        assertThrows(BusinessException.class, () -> service.validate("CASE_DESIGN", "{}"));
        assertThrows(BusinessException.class, () -> service.validate("QUALITY_REVIEW", "not-json"));
        assertThrows(BusinessException.class, () -> service.validate("UNKNOWN", "{}"));
    }
}
