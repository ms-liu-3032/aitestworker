package com.company.aitest.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.TimeProvider;
import com.company.aitest.knowledge.KnowledgeDepositionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class AttachmentParseWorkerTest {

    @Test
    void depositionFailureDoesNotTurnParsedAttachmentIntoParseFailure() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcTemplate template = mock(JdbcTemplate.class);
        FileStorageService storage = mock(FileStorageService.class);
        DocumentParseService parser = mock(DocumentParseService.class);
        ImageUnderstandingService vision = mock(ImageUnderstandingService.class);
        TimeProvider time = mock(TimeProvider.class);
        KnowledgeDepositionService deposition = mock(KnowledgeDepositionService.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 7, 21, 10, 0));
        when(storage.resolve("requirements.pdf")).thenReturn(Path.of("/tmp/requirements.pdf"));
        when(parser.parse(anyString(), anyString())).thenReturn("已解析的需求内容");
        when(template.update(anyString(), any(Object[].class))).thenReturn(1);
        when(deposition.depositUploadedDocument(any(), anyString(), any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("wiki unavailable"));

        JdbcClient.StatementSpec parseSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec parseQuery = mock(JdbcClient.MappedQuerySpec.class);
        when(parseSpec.query(any(RowMapper.class))).thenReturn(parseQuery);
        when(parseQuery.list()).thenReturn(List.of(java.util.Map.of(
                    "id", 8L, "session_id", 3L, "file_name", "requirements.pdf",
                    "file_type", "pdf", "storage_path", "requirements.pdf", "created_by", 7L)));

        JdbcClient.StatementSpec depositionSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec depositionQuery = mock(JdbcClient.MappedQuerySpec.class);
        when(depositionSpec.query(any(RowMapper.class))).thenReturn(depositionQuery);
        when(depositionQuery.list()).thenReturn(List.of(java.util.Map.of(
                "id", 8L, "file_name", "requirements.pdf", "created_by", 7L,
                "project_id", 2L, "deposit_content", "已解析的需求内容")));
        when(jdbc.sql(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("knowledge_deposition_status") ? depositionSpec : parseSpec;
        });

        AttachmentParseWorker worker = new AttachmentParseWorker(jdbc, template, storage, parser, vision, time);
        worker.setKnowledgeDepositionService(deposition);
        worker.processPending();

        verify(template).update(contains("parse_status = 'PARSED'"), any(Object[].class));
        verify(template).update(contains("knowledge_deposition_status = 'FAILED'"), any(Object[].class));
        verify(template, never()).update(contains("parse_status = 'FAILED'"), any(Object[].class));
    }
}
