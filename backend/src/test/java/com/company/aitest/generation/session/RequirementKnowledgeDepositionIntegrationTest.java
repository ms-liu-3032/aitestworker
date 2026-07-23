package com.company.aitest.generation.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.knowledge.KnowledgeDepositionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class RequirementKnowledgeDepositionIntegrationTest {

    @Test
    void requirementScopeConfirmationDepositsConfirmedAnalysisAndClarification() {
        JdbcTemplate template = mock(JdbcTemplate.class);
        GenerationSessionService sessions = mock(GenerationSessionService.class);
        GenerationMessageService messages = mock(GenerationMessageService.class);
        TimeProvider time = mock(TimeProvider.class);
        KnowledgeDepositionService deposition = mock(KnowledgeDepositionService.class);
        CurrentUser user = new CurrentUser(7L, "tester", "USER");
        var session = session();
        when(sessions.get(null, 1L, user)).thenReturn(session);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 7, 21, 10, 0));
        String result = """
                {"requirement_atoms":[{"id":"R1","title":"审批","requirement":"提交后审批","generation_scope":"GENERATE"}]}
                """;
        var analysis = analysis(result, "[]", "NEED_SCOPE_CONFIRMATION");
        var service = spy(new RequirementAnalysisService(null, template, time, null, sessions, messages,
                null, null, null, null, null, null));
        service.setKnowledgeDepositionService(deposition);
        doReturn(analysis).when(service).getAnalysis(1L, 1);

        service.confirmRequirementScope(1L, 1,
                List.of(new RequirementAnalysisService.RequirementScopeDecision("R1", "GENERATE", "本期范围")), user);

        verify(deposition).depositConfirmedRequirement(eq(9L), eq(22L), eq(1),
                anyString(), eq(analysis.clarificationQuestions()), eq(analysis.clarificationAnswers()), eq(user));
    }

    @Test
    void testPointScopeConfirmationDepositsOnlyAfterHumanScopeGate() {
        JdbcTemplate template = mock(JdbcTemplate.class);
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Integer> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(Integer.class)).thenReturn(mapped);
        when(mapped.single()).thenReturn(0);
        GenerationSessionService sessions = mock(GenerationSessionService.class);
        GenerationMessageService messages = mock(GenerationMessageService.class);
        TimeProvider time = mock(TimeProvider.class);
        KnowledgeDepositionService deposition = mock(KnowledgeDepositionService.class);
        CurrentUser user = new CurrentUser(7L, "tester", "USER");
        when(sessions.get(null, 1L, user)).thenReturn(session());
        when(time.now()).thenReturn(LocalDateTime.of(2026, 7, 21, 10, 0));
        String points = "[{\"id\":\"TP1\",\"title\":\"审批通过\",\"generation_scope\":\"GENERATE\"}]";
        var analysis = analysis("{}", points, "NEED_TEST_POINT_SCOPE_CONFIRMATION");
        var service = spy(new RequirementAnalysisService(jdbc, template, time, null, sessions, messages,
                null, null, null, null, null, null));
        service.setKnowledgeDepositionService(deposition);
        doReturn(analysis).when(service).getAnalysis(1L, 1);

        service.confirmTestPointScope(1L, 1,
                List.of(new RequirementAnalysisService.TestPointScopeDecision("TP1", "GENERATE", "确认生成")), user);

        verify(deposition).depositConfirmedTestPoints(eq(9L), eq(22L), eq(1), anyString(), eq(user));
    }

    private GenerationSessionRecord session() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 21, 9, 0);
        return new GenerationSessionRecord(1L, 9L, "会话", "ACTIVE", "WAITING_USER_CONFIRMATION",
                3L, null, null, true, "PROJECT_AND_SYSTEM_TOM", 1, 5L, 7L, now, now);
    }

    private RequirementAnalysisRecord analysis(String result, String points, String status) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 21, 9, 0);
        return new RequirementAnalysisRecord(22L, 1L, 1, 0, "需求", result, null,
                "[{\"question\":\"超时怎么办\"}]", "[{\"index\":0,\"answer\":\"自动驳回\"}]",
                "[]", points, null, null, null, status, now, now);
    }
}
