package com.company.aitest.generation.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class GenerationSessionServiceTest {

    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TimeProvider timeProvider;
    @Mock
    private JdbcClient.StatementSpec countSpec;
    @Mock
    private JdbcClient.StatementSpec listSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<Integer> countQuery;
    @Mock
    private JdbcClient.MappedQuerySpec<GenerationSessionRecord> listQuery;
    @Mock
    private JdbcClient.StatementSpec getSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<GenerationSessionRecord> getQuery;

    private GenerationSessionService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GenerationSessionService(jdbcClient, jdbcTemplate, timeProvider);
        user = new CurrentUser(7L, "alice", "USER");
    }

    @Test
    void listExcludesArchivedSessionsByDefault() {
        mockListQueries();

        service.list(2L, 1, 20, null, null, user);

        verify(jdbcClient).sql("SELECT COUNT(*) FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status <> 'ARCHIVED'");
        verify(jdbcClient).sql("SELECT * FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status <> 'ARCHIVED' ORDER BY updated_at DESC LIMIT :limit OFFSET :offset");
    }

    @Test
    void listKeepsExplicitArchivedFilterWhenRequested() {
        mockListQueries();

        service.list(2L, 1, 20, "ARCHIVED", null, user);

        verify(jdbcClient).sql("SELECT COUNT(*) FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status = :status");
        verify(jdbcClient).sql("SELECT * FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status = :status ORDER BY updated_at DESC LIMIT :limit OFFSET :offset");
    }

    @Test
    void getRequiresCurrentUserOwnership() {
        mockGetQuery();

        service.get(2L, 99L, user);

        verify(jdbcClient).sql("SELECT * FROM generation_session WHERE id = :id AND project_id = :pid AND created_by = :uid");
    }

    @SuppressWarnings("unchecked")
    private void mockListQueries() {
        when(jdbcClient.sql(eq("SELECT COUNT(*) FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status <> 'ARCHIVED'")))
                .thenReturn(countSpec);
        when(jdbcClient.sql(eq("SELECT * FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status <> 'ARCHIVED' ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")))
                .thenReturn(listSpec);
        when(jdbcClient.sql(eq("SELECT COUNT(*) FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status = :status")))
                .thenReturn(countSpec);
        when(jdbcClient.sql(eq("SELECT * FROM generation_session WHERE project_id = :projectId AND created_by = :createdBy AND status = :status ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")))
                .thenReturn(listSpec);

        when(countSpec.params(anyMap())).thenReturn(countSpec);
        when(countSpec.query(Integer.class)).thenReturn(countQuery);
        when(countQuery.single()).thenReturn(0);

        when(listSpec.param(eq("limit"), any())).thenReturn(listSpec);
        when(listSpec.param(eq("offset"), any())).thenReturn(listSpec);
        when(listSpec.params(anyMap())).thenReturn(listSpec);
        when(listSpec.query(any(RowMapper.class))).thenReturn(listQuery);
        when(listQuery.list()).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void mockGetQuery() {
        when(jdbcClient.sql(eq("SELECT * FROM generation_session WHERE id = :id AND project_id = :pid AND created_by = :uid")))
                .thenReturn(getSpec);
        when(getSpec.param(eq("id"), any())).thenReturn(getSpec);
        when(getSpec.param(eq("pid"), any())).thenReturn(getSpec);
        when(getSpec.param(eq("uid"), any())).thenReturn(getSpec);
        when(getSpec.query(any(RowMapper.class))).thenReturn(getQuery);
        when(getQuery.list()).thenReturn(List.of(mock(GenerationSessionRecord.class)));
    }
}
