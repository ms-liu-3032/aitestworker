package com.company.aitest.llm.gateway.retrieval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import com.company.aitest.llm.gateway.LlmInvocationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagRetrievalServiceDshSearchTest {
    @Test
    void dshKeywordIsBoundIntoAllThreeAssetQueries() throws Exception {
        var jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of());
        var retrieval = new RagRetrievalService(jdbcTemplate, mock(JdbcClient.class));
        var request = new LlmInvocationRequest(null, 9L, 2L, null,
                "DSH_ASSET_SEARCH", null, null, null, null, Map.of(), null, null, null);
        var policy = new RetrievalPolicy(false, null, 8, false, false, "访客_");

        assertTrue(retrieval.searchForDsh(request, policy).assets().isEmpty());

        @SuppressWarnings("unchecked")
        var creators = org.mockito.ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbcTemplate, times(3)).query(creators.capture(), any(RowMapper.class));
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        for (var creator : creators.getAllValues()) creator.createPreparedStatement(connection);
        verify(connection, times(3)).prepareStatement(argThat(sql -> sql.contains("LIKE ? ESCAPE '!'")));
        verify(statement, atLeast(3)).setString(anyInt(), eq("%访客!_%"));
    }
}
