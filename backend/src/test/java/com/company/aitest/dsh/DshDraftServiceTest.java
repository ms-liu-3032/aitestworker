package com.company.aitest.dsh;

import java.util.List;
import java.util.Optional;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DshDraftServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void snapshotsApprovedSourceContentAndVersion() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        var resultSet = mock(java.sql.ResultSet.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(resultSet.getString("content_hash")).thenReturn("knowledge-hash");
        when(resultSet.getInt("version")).thenReturn(3);
        when(resultSet.getString("case_title")).thenReturn("已审核用例");
        when(resultSet.getString("steps")).thenReturn("执行步骤");
        when(resultSet.getString("expected_result")).thenReturn("预期结果");
        when(resultSet.getString("skill_name")).thenReturn("测试方法");
        when(resultSet.getString("flow_steps")).thenReturn("方法步骤");
        when(statement.query(any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<DshDraftService.ReferenceSnapshot> mapper = invocation.getArgument(0);
            JdbcClient.MappedQuerySpec<DshDraftService.ReferenceSnapshot> query = mock(JdbcClient.MappedQuerySpec.class);
            DshDraftService.ReferenceSnapshot snapshot = mapper.mapRow(resultSet, 0);
            when(query.optional()).thenReturn(Optional.of(snapshot));
            return query;
        });
        var service = new DshDraftService(jdbc, mock(JdbcTemplate.class), mock(TimeProvider.class),
                mock(ProjectAccessService.class), new ObjectMapper(), mock(DshWorkSessionService.class),
                mock(DshCaptureService.class));
        var user = new CurrentUser(7L, "tester", "MEMBER");

        var refs = service.resolveSourceRefs(1L, user,
                List.of("knowledge_document#2", "test_case_asset#3", "test_skill_template#4"));

        assertEquals(3, refs.size());
        assertEquals("knowledge_document#2", refs.get(0).ref());
        assertEquals("knowledge-hash", refs.get(0).contentHash());
        assertEquals(3, refs.get(1).version());
        assertEquals(64, refs.get(1).contentHash().length());
        assertEquals(3, refs.get(2).version());
        assertEquals(64, refs.get(2).contentHash().length());
        assertTrue(refs.get(1).contentHash().matches("[0-9a-f]{64}"));
        verify(jdbc, atLeastOnce()).sql(contains("review_status = 'APPROVED'"));
        verify(jdbc).sql(contains("case_status = 'SUBMITTED'"));
        verify(jdbc).sql(contains("status = 'ACTIVE'"));
    }

    @Test
    void rejectsInvalidDraftBeforeAnyWrite() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcTemplate template = mock(JdbcTemplate.class);
        var service = new DshDraftService(jdbc, template, mock(TimeProvider.class),
                mock(ProjectAccessService.class), new ObjectMapper(), mock(DshWorkSessionService.class),
                mock(DshCaptureService.class));
        var user = new CurrentUser(7L, "tester", "MEMBER");

        assertThrows(BusinessException.class, () -> service.save(1L,
                new DshDraftService.DraftInput(8L, "key", "需求", "x".repeat(256), null,
                        null, "操作", "结果", "P2", null, List.of(), null), user));
        assertThrows(BusinessException.class, () -> service.save(1L,
                new DshDraftService.DraftInput(8L, "key", "需求", "标题", null,
                        null, "操作", "结果", "P2", null, List.of("x".repeat(513)), null), user));
        assertThrows(BusinessException.class, () -> service.save(1L,
                new DshDraftService.DraftInput(null, "key", "需求", "标题", null,
                        null, "操作", "结果", "P2", null, List.of(), null), user));

        verifyNoInteractions(jdbc, template);
    }
}
