package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
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
    private JdbcClient jdbcClient;
    @Mock
    private JdbcClient.StatementSpec draftListSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<GenerationSessionController.GenerationDraftView> draftListQuery;

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
}
