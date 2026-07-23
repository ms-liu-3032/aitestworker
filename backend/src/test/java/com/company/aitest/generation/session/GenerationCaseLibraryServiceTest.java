package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class GenerationCaseLibraryServiceTest {

    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TimeProvider timeProvider;
    @Mock
    private JdbcClient.StatementSpec listSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<GenerationCaseLibraryService.LocalCaseDraftView> listQuery;
    @Mock
    private JdbcClient.StatementSpec traceListSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<GenerationCaseLibraryService.LocalCaseDraftView> traceListQuery;
    @Mock
    private JdbcClient.StatementSpec getSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<GenerationCaseLibraryService.LocalCaseDraftView> getQuery;

    private GenerationCaseLibraryService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GenerationCaseLibraryService(jdbcClient, jdbcTemplate, timeProvider);
        user = new CurrentUser(9L, "alice", "USER");
    }

    @Test
    void listUsesProjectAndCurrentUser() {
        when(jdbcClient.sql(eq("""
                SELECT *,
                       'GENERATION' AS source_type
                FROM test_case_draft
                WHERE project_id = :projectId
                  AND created_by = :createdBy
                """))).thenReturn(listSpec);
        when(jdbcClient.sql(eq("""
                SELECT -tgc.id AS id,
                       0 AS task_id,
                       tgc.project_id,
                       CONCAT('TRACE-', tgc.id) AS case_no,
                       tgc.case_title,
                       COALESCE(p.project_name, '') AS project_name,
                       tgc.module_name,
                       tgc.precondition,
                       tgc.steps,
                       tgc.expected_result,
                       tgc.priority,
                       tgc.case_type,
                       'POSITIVE' AS scenario_type,
                       '轨迹回放法' AS design_method,
                       tgc.source_refs_json,
                       tgc.case_scope,
                       tgc.case_status,
                       tgc.user_id AS created_by,
                       tgc.created_at,
                       tgc.updated_at,
                       tgc.trace_session_id AS session_id,
                       'TRACE' AS source_type
                FROM trace_generated_case tgc
                LEFT JOIN project p ON p.id = tgc.project_id
                WHERE tgc.project_id = :projectId
                  AND tgc.user_id = :createdBy
                """))).thenReturn(traceListSpec);
        when(listSpec.param(eq("projectId"), any())).thenReturn(listSpec);
        when(listSpec.param(eq("createdBy"), any())).thenReturn(listSpec);
        when(listSpec.query(any(RowMapper.class))).thenReturn(listQuery);
        when(listQuery.list()).thenReturn(List.of());
        when(traceListSpec.param(eq("projectId"), any())).thenReturn(traceListSpec);
        when(traceListSpec.param(eq("createdBy"), any())).thenReturn(traceListSpec);
        when(traceListSpec.query(any(RowMapper.class))).thenReturn(traceListQuery);
        when(traceListQuery.list()).thenReturn(List.of());

        service.list(2L, user);

        verify(jdbcClient).sql(eq("""
                SELECT *,
                       'GENERATION' AS source_type
                FROM test_case_draft
                WHERE project_id = :projectId
                  AND created_by = :createdBy
                """));
        verify(jdbcClient).sql(eq("""
                SELECT -tgc.id AS id,
                       0 AS task_id,
                       tgc.project_id,
                       CONCAT('TRACE-', tgc.id) AS case_no,
                       tgc.case_title,
                       COALESCE(p.project_name, '') AS project_name,
                       tgc.module_name,
                       tgc.precondition,
                       tgc.steps,
                       tgc.expected_result,
                       tgc.priority,
                       tgc.case_type,
                       'POSITIVE' AS scenario_type,
                       '轨迹回放法' AS design_method,
                       tgc.source_refs_json,
                       tgc.case_scope,
                       tgc.case_status,
                       tgc.user_id AS created_by,
                       tgc.created_at,
                       tgc.updated_at,
                       tgc.trace_session_id AS session_id,
                       'TRACE' AS source_type
                FROM trace_generated_case tgc
                LEFT JOIN project p ON p.id = tgc.project_id
                WHERE tgc.project_id = :projectId
                  AND tgc.user_id = :createdBy
                """));
    }

    @Test
    void submitRejectsAlreadySubmittedDraft() {
        GenerationCaseLibraryService.LocalCaseDraftView draft = new GenerationCaseLibraryService.LocalCaseDraftView(
                3L, 11L, 2L, "TC-1", "title", "project", "module", "pre", "steps", "expected",
                "P1", "FUNCTIONAL", "POSITIVE", "LLM生成", "{}", "PERSONAL", "SUBMITTED", 9L,
                LocalDateTime.now(), LocalDateTime.now(), 7L, "GENERATION"
        );
        when(jdbcClient.sql(eq("""
                SELECT *,
                       'GENERATION' AS source_type
                FROM test_case_draft
                WHERE id = :id
                  AND project_id = :projectId
                  AND created_by = :createdBy
                """))).thenReturn(getSpec);
        when(getSpec.param(eq("id"), any())).thenReturn(getSpec);
        when(getSpec.param(eq("projectId"), any())).thenReturn(getSpec);
        when(getSpec.param(eq("createdBy"), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(draft));

        assertThrows(BusinessException.class, () -> service.submitToFormal(2L, 3L, user));
    }
}
