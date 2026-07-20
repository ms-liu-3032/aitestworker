package com.company.aitest.wiki;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class WikiServiceTest {

    private final TimeProvider timeProvider = mock(TimeProvider.class);
    {
        when(timeProvider.now()).thenReturn(LocalDateTime.of(2025, 1, 1, 12, 0));
    }

    @Test
    void listPacks_queriesByProjectId() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = new WikiService(jdbc, tmpl, timeProvider);

        var spec = mock(JdbcClient.StatementSpec.class);
        var mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("wiki_pack"))).thenReturn(spec);
        when(spec.param(eq(10L))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of(
                pack(1L, 10L, "PROJECT", "P1"),
                pack(2L, 10L, "SYSTEM", "P2")
        ));

        var result = svc.listPacks(10L);
        assertEquals(2, result.size());
        assertEquals("PROJECT", result.get(0).scope());
        assertEquals("SYSTEM", result.get(1).scope());
    }

    @Test
    void createPack_insertsAndReturns() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = new WikiService(jdbc, tmpl, timeProvider);

        var spec = mock(JdbcClient.StatementSpec.class);
        var mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("SELECT * FROM wiki_pack"))).thenReturn(spec);
        when(spec.param(eq(1L))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of(
                pack(1L, 10L, "PROJECT", "TestPack")
        ));
        when(jdbc.sql(contains("LAST_INSERT_ID"))).thenReturn(spec);
        when(spec.query(Long.class)).thenReturn(mapped);
        when(mapped.single()).thenReturn(1L);

        var user = new CurrentUser(1L, "admin", null);
        var result = svc.createPack(10L, "PROJECT", "TestPack", "desc", user);

        verify(tmpl).update(contains("INSERT INTO wiki_pack"), eq(10L), eq("PROJECT"), eq("TestPack"), eq("desc"), eq(1L), any(), any());
        assertEquals("TestPack", result.name());
        assertEquals("PROJECT", result.scope());
    }

    @Test
    void createEntry_insertsAndReturns() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = new WikiService(jdbc, tmpl, timeProvider);

        var spec = mock(JdbcClient.StatementSpec.class);
        var mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("SELECT * FROM wiki_entry"))).thenReturn(spec);
        when(spec.param(eq(1L))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of(
                entry(1L, 1L, "RULE", "Rule1")
        ));
        when(jdbc.sql(contains("LAST_INSERT_ID"))).thenReturn(spec);
        when(spec.query(Long.class)).thenReturn(mapped);
        when(mapped.single()).thenReturn(1L);

        var user = new CurrentUser(1L, "admin", null);
        var result = svc.createEntry(1L, "RULE", "Rule1", "content", null, null, user);

        verify(tmpl).update(contains("INSERT INTO wiki_entry"), eq(1L), eq("RULE"), eq("Rule1"), eq("content"), isNull(), isNull(), eq(1L), any(), any());
        assertEquals("RULE", result.entryType());
        assertEquals("Rule1", result.title());
        assertEquals("PENDING", result.reviewStatus());
    }

    @Test
    void reviewEntry_updatesReviewStatus() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = new WikiService(jdbc, tmpl, timeProvider);

        var spec = mock(JdbcClient.StatementSpec.class);
        var mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("SELECT * FROM wiki_entry"))).thenReturn(spec);
        when(spec.param(eq(1L))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of(
                new WikiEntryRecord(1L, 1L, "RULE", "Rule1", "content", null, null, "APPROVED", null, "ACTIVE", 1L, LocalDateTime.now(), LocalDateTime.now())
        ));
        when(tmpl.update(anyString(), any(), any(), any())).thenReturn(1);

        var user = new CurrentUser(1L, "admin", null);
        var result = svc.reviewEntry(1L, "APPROVED", user);

        verify(tmpl).update(contains("review_status"), eq("APPROVED"), any(), eq(1L));
        assertEquals("APPROVED", result.reviewStatus());
    }

    @Test
    void deletePack_deletesEntriesFirst() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = new WikiService(jdbc, tmpl, timeProvider);

        svc.deletePack(1L);

        verify(tmpl).update(contains("DELETE FROM wiki_entry"), eq(1L));
        verify(tmpl).update(contains("DELETE FROM wiki_pack"), eq(1L));
    }

    @Test
    void wikiRecord_fieldValues() {
        var now = LocalDateTime.now();
        var pack = pack(1L, 10L, "PROJECT", "P1");
        assertEquals(1L, pack.id());
        assertEquals(10L, pack.projectId());
        assertEquals("PROJECT", pack.scope());
        assertEquals("P1", pack.name());

        var e = entry(1L, 1L, "RULE", "R1");
        assertEquals(1L, e.id());
        assertEquals(1L, e.packId());
        assertEquals("RULE", e.entryType());

        var r = new WikiEntryRelationRecord(1L, 1L, 2L, 3L, "RELATED_TOM");
        assertEquals(1L, r.entryId());
        assertEquals(2L, r.relatedTomId());
        assertEquals(3L, r.relatedBusinessPackId());
    }

    private WikiPackRecord pack(Long id, Long projectId, String scope, String name) {
        return new WikiPackRecord(id, projectId, scope, name, "DRAFT", "PENDING", null, null, null, 1L, LocalDateTime.now(), LocalDateTime.now());
    }

    private WikiEntryRecord entry(Long id, Long packId, String type, String title) {
        return new WikiEntryRecord(id, packId, type, title, "content", null, null, "PENDING", null, "ACTIVE", 1L, LocalDateTime.now(), LocalDateTime.now());
    }
}
