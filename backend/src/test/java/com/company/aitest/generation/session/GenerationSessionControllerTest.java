package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.AsyncGenerationTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class GenerationSessionControllerTest {

    @Mock
    private GenerationSessionService sessionService;
    @Mock
    private GenerationMessageService messageService;
    @Mock
    private GenerationAttachmentService attachmentService;
    @Mock
    private RequirementAnalysisService analysisService;
    @Mock
    private ConversationOrchestrator orchestrator;
    @Mock
    private AsyncGenerationTaskService asyncGenerationTaskService;
    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcClient.StatementSpec draftListSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<GenerationSessionController.GenerationDraftView> draftListQuery;
    @Mock
    private JdbcClient.MappedQuerySpec<Integer> countQuery;

    private GenerationSessionController controller;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new GenerationSessionController(
                sessionService,
                messageService,
                attachmentService,
                analysisService,
                orchestrator,
                asyncGenerationTaskService,
                jdbcClient
        );
        auth = new UsernamePasswordAuthenticationToken(
                new CurrentUser(1L, "tester", "Tester"),
                null,
                List.of()
        );
    }

    @Test
    void listMessagesChecksProjectOwnershipBeforeLoadingMessages() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);

        controller.listMessages(100L, 200L, auth);

        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
        verify(messageService).listMessages(200L);
    }

    @Test
    void sendMessageStopsWhenSessionDoesNotBelongToProject() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenThrow(new BusinessException("会话不存在"));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> controller.sendMessage(100L, 200L, Map.of("content", "hello"), auth)
        );

        assertEquals("会话不存在", ex.getMessage());
        verify(orchestrator, never()).processUserMessage(any(), any(), any());
    }

    @Test
    void getLatestAnalysisChecksProjectOwnershipBeforeLoadingAnalysis() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);

        controller.getLatestAnalysis(100L, 200L, auth);

        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
        verify(analysisService).getLatestAnalysis(200L);
    }

    @Test
    void confirmTestPointScopeChecksOwnershipAndDelegatesCompleteDecisionSet() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 12, 0);
        var decisions = List.of(new RequirementAnalysisService.TestPointScopeDecision("TP1", "GENERATE", "本期范围"));
        var task = new AsyncGenerationTaskService.TaskView(402L, "TEST_POINT_SCOPE_CONTINUATION",
                "PENDING", null, null, 0, now, now);
        when(asyncGenerationTaskService.confirmTestPointScopeAndContinue(eq(100L), eq(200L), eq(3),
                eq(decisions), any(CurrentUser.class))).thenReturn(task);

        var response = controller.confirmTestPointScope(100L, 200L, 3,
                new GenerationSessionController.TestPointScopeRequest(decisions), auth);

        assertEquals(402L, response.data().taskId());
        assertEquals("TEST_POINT_SCOPE_CONTINUATION", response.data().taskType());
        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
        verify(asyncGenerationTaskService).confirmTestPointScopeAndContinue(eq(100L), eq(200L), eq(3),
                eq(decisions), any(CurrentUser.class));
    }

    @Test
    void confirmRequirementScopeStartsSeparateContinuationTask() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);
        var decisions = List.of(
                new RequirementAnalysisService.RequirementScopeDecision("R1", "GENERATE", "本期新增"),
                new RequirementAnalysisService.RequirementScopeDecision("R2", "EXCLUDED", "明确非本期"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 13, 0);
        var task = new AsyncGenerationTaskService.TaskView(401L, "REQUIREMENT_SCOPE_CONTINUATION",
                "PENDING", null, null, 0, now, now);
        when(asyncGenerationTaskService.confirmRequirementScopeAndContinue(eq(100L), eq(200L), eq(3),
                eq(decisions), any(CurrentUser.class))).thenReturn(task);

        var response = controller.confirmRequirementScope(100L, 200L, 3,
                new GenerationSessionController.RequirementScopeRequest(decisions), auth);

        assertEquals(401L, response.data().taskId());
        assertEquals("REQUIREMENT_SCOPE_CONTINUATION", response.data().taskType());
        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
        verify(asyncGenerationTaskService).confirmRequirementScopeAndContinue(eq(100L), eq(200L), eq(3),
                eq(decisions), any(CurrentUser.class));
    }

    @Test
    void listDraftsChecksProjectOwnershipBeforeQueryingDrafts() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenThrow(new BusinessException("会话不存在"));

        assertThrows(BusinessException.class, () -> controller.listDrafts(100L, 200L, auth));

        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
    }

    @Test
    void listDraftsReturnsStructuredDraftsWithStatus() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);
        when(jdbcClient.sql(eq("""
                SELECT id, session_id, analysis_id, analysis_version,
                       case_title, module_name, precondition, steps,
                       expected_result, priority, case_type,
                       case_status AS status, source_refs_json,
                       quality_status, created_at, updated_at
                FROM test_case_draft
                WHERE session_id = :sid ORDER BY id ASC
                """))).thenReturn(draftListSpec);
        when(draftListSpec.param(eq("sid"), eq(200L))).thenReturn(draftListSpec);
        when(draftListSpec.query(any(RowMapper.class))).thenReturn(draftListQuery);

        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 20, 30);
        var draft = new GenerationSessionController.GenerationDraftView(
                7L, 200L, 99L, 3, "登录成功", "认证", "用户已注册", "1. 打开登录页", "进入首页",
                "P1", "FUNCTIONAL", "DRAFT", "{\"source\":\"REQUIREMENT_ANALYSIS\"}", "PASS", now, now
        );
        when(draftListQuery.list()).thenReturn(List.of(draft));

        var response = controller.listDrafts(100L, 200L, auth);

        assertEquals(1, response.data().size());
        assertEquals("DRAFT", response.data().get(0).status());
        assertEquals("登录成功", response.data().get(0).caseTitle());
        assertEquals(99L, response.data().get(0).analysisId());
        assertEquals(3, response.data().get(0).analysisVersion());
        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
    }

    @Test
    void analysisAsyncStartsRequirementAnalysisTask() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 17, 20);
        var task = new AsyncGenerationTaskService.TaskView(
                301L, "REQUIREMENT_ANALYSIS", "PENDING", null, null, 0, now, now);
        when(asyncGenerationTaskService.startSessionRequirementAnalysis(eq(100L), eq(200L), eq("使用 TOM"), any(CurrentUser.class)))
                .thenReturn(task);

        var response = controller.analysisAsync(100L, 200L, Map.of("content", "使用 TOM"), auth);

        assertEquals(301L, response.data().taskId());
        assertEquals("REQUIREMENT_ANALYSIS", response.data().taskType());
        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
        verify(asyncGenerationTaskService).startSessionRequirementAnalysis(eq(100L), eq(200L), eq("使用 TOM"), any(CurrentUser.class));
    }

    @Test
    void generateAsyncChecksProjectOwnershipBeforeStartingTask() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class)))
                .thenThrow(new BusinessException("会话不存在"));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> controller.generateAsync(100L, 200L, Map.of("content", "生成用例"), auth)
        );

        assertEquals("会话不存在", ex.getMessage());
        verify(asyncGenerationTaskService, never()).startSessionCaseGeneration(any(), any(), any());
        verify(messageService, never()).appendUserMessage(any(), any(), any());
        verify(messageService, never()).appendAssistantMessage(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void generateAsyncStartsTaskAndWritesConversationMessages() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 14, 50);
        var task = new AsyncGenerationTaskService.TaskView(
                300L, "TEST_CASE_GENERATION", "PENDING", null, null, 0, now, now);
        when(asyncGenerationTaskService.startSessionCaseGeneration(eq(100L), eq(200L), any(CurrentUser.class)))
                .thenReturn(task);
        when(analysisService.getLatestAnalysis(200L)).thenReturn(new RequirementAnalysisRecord(
                88L, 200L, 4, 0, "需求", "{}", "{}", "[]", "[]", "[]", "[]",
                "[]", "MINOR", "[]", "CONFIRMED", now, now));

        var response = controller.generateAsync(100L, 200L, Map.of("content", "生成用例"), auth);

        assertEquals(300L, response.data().taskId());
        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
        verify(asyncGenerationTaskService).startSessionCaseGeneration(eq(100L), eq(200L), any(CurrentUser.class));
        verify(messageService).appendUserMessage(eq(200L), eq("生成用例"), any(CurrentUser.class));
        verify(messageService).appendAssistantMessage(eq(200L), org.mockito.ArgumentMatchers.contains("#300"),
                eq(null), eq("SYSTEM_CASE_GENERATING"), eq(4));
    }


    @Test
    void generateIncrementalAsyncStartsTaskAfterDraftOwnershipCheck() {
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(null);
        when(jdbcClient.sql(eq("SELECT COUNT(*) FROM test_case_draft WHERE session_id = :sid AND id IN (:ids)")))
                .thenReturn(draftListSpec);
        when(draftListSpec.param(eq("sid"), eq(200L))).thenReturn(draftListSpec);
        when(draftListSpec.param(eq("ids"), eq(List.of(7, 8)))).thenReturn(draftListSpec);
        when(draftListSpec.query(Integer.class)).thenReturn(countQuery);
        when(countQuery.single()).thenReturn(2);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 18, 30);
        var task = new AsyncGenerationTaskService.TaskView(
                302L, "INCREMENTAL_CASE_GENERATION", "PENDING", null, null, 0, now, now);
        when(asyncGenerationTaskService.startSessionIncrementalGeneration(eq(100L), eq(200L), eq(List.of(7, 8)), any(CurrentUser.class)))
                .thenReturn(task);

        var response = controller.generateIncrementalAsync(100L, 200L, Map.of("selectedDraftIds", List.of(7, 8)), auth);

        assertEquals(302L, response.data().taskId());
        assertEquals("INCREMENTAL_CASE_GENERATION", response.data().taskType());
        verify(sessionService).get(eq(100L), eq(200L), any(CurrentUser.class));
        verify(asyncGenerationTaskService).startSessionIncrementalGeneration(eq(100L), eq(200L), eq(List.of(7, 8)), any(CurrentUser.class));
    }

    @Test
    void generateIncremental_throwsWhenSelectedDraftIdsIsNull() {
        Map<String, Object> body = Map.of();
        assertThrows(BusinessException.class, () -> controller.generateIncremental(100L, 200L, body, auth));
    }

    @Test
    void generateIncremental_throwsWhenSelectedDraftIdsIsNotArray() {
        Map<String, Object> body = Map.of("selectedDraftIds", "not_array");
        assertThrows(BusinessException.class, () -> controller.generateIncremental(100L, 200L, body, auth));
    }

    @Test
    void generateIncremental_throwsWhenSelectedDraftIdsContainsInvalidElements() {
        Map<String, Object> body = Map.of("selectedDraftIds", java.util.List.of("abc", "1.5"));
        assertThrows(BusinessException.class, () -> controller.generateIncremental(100L, 200L, body, auth));
    }

    @Test
    void updateConfigPersistsExplicitProjectOnlyTomMode() {
        GenerationSessionRecord current = mock(GenerationSessionRecord.class);
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(current);

        controller.update(100L, 200L, Map.of(
                "modelConfigId", 12L,
                "promptTemplateId", 13L,
                "tomMode", "PROJECT_TOM"
        ), auth);

        verify(sessionService).updateConfig(eq(100L), eq(200L), eq(12L), eq(13L),
                eq("PROJECT_TOM"), any(CurrentUser.class));
    }

    @Test
    void updateConfigPreservesOmittedFieldsAndTomMode() {
        GenerationSessionRecord current = mock(GenerationSessionRecord.class);
        when(current.modelConfigId()).thenReturn(12L);
        when(current.promptTemplateId()).thenReturn(13L);
        when(current.tomMode()).thenReturn("PROJECT_TOM");
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(current);

        controller.update(100L, 200L, Map.of("modelConfigId", 22L), auth);

        verify(sessionService).updateConfig(eq(100L), eq(200L), eq(22L), eq(13L),
                eq("PROJECT_TOM"), any(CurrentUser.class));
    }

    @Test
    void updateConfigAllowsExplicitlyClearingModelWithoutResettingTomMode() {
        GenerationSessionRecord current = mock(GenerationSessionRecord.class);
        when(current.modelConfigId()).thenReturn(12L);
        when(current.promptTemplateId()).thenReturn(13L);
        when(current.tomMode()).thenReturn("PROJECT_TOM");
        when(sessionService.get(eq(100L), eq(200L), any(CurrentUser.class))).thenReturn(current);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("modelConfigId", null);

        controller.update(100L, 200L, body, auth);

        verify(sessionService).updateConfig(eq(100L), eq(200L), eq(null), eq(13L),
                eq("PROJECT_TOM"), any(CurrentUser.class));
    }
}
