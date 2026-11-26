package com.company.aitest.asset;

import java.util.List;

import com.company.aitest.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AssetInventoryServiceTest {
    @Test
    void rejectsInvalidProjectBeforeQuerying() {
        JdbcClient jdbc = mock(JdbcClient.class);
        assertThrows(BusinessException.class, () -> new AssetInventoryService(jdbc).list(-1L));
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bindsProjectIdAndReturnsAggregatedRows() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<AssetInventoryService.InventoryRow> query = mock(JdbcClient.MappedQuerySpec.class);
        var row = new AssetInventoryService.InventoryRow("TOM_NODE", "PROJECT", "CANDIDATE", "MANUAL_IMPORT", 3);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("projectId", 18L)).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of(row));

        assertEquals(List.of(row), new AssetInventoryService(jdbc).list(18L));
        verify(statement).param("projectId", 18L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullProjectListsAllProjectsWithBoundSentinel() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<AssetInventoryService.InventoryRow> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("projectId", 0L)).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        assertEquals(List.of(), new AssetInventoryService(jdbc).list(null));
        verify(statement).param("projectId", 0L);
    }
}
