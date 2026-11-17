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

    @Test
    void changingTomLinksRequiresActiveVisibleTomAndResetsEntryReview() {
        var jdbc = mock(JdbcClient.class);
        var template = mock(JdbcTemplate.class);
        var svc = spy(new WikiService(jdbc, template, timeProvider));
        var pack = new WikiPackRecord(4L, 10L, "PROJECT", "规则", "ACTIVE", "APPROVED",
                null, null, null, "", 1L, LocalDateTime.now(), LocalDateTime.now());
        doReturn(pack).when(svc).getPackForEntry(6L);
        doReturn(List.of()).when(svc).listTomLinks(6L);
        var spec = mock(JdbcClient.StatementSpec.class);
        var rows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(startsWith("SELECT id FROM test_object_model"))).thenReturn(spec);
        when(spec.param(11L)).thenReturn(spec);
        when(spec.param(10L)).thenReturn(spec);
        when(spec.query(Long.class)).thenReturn(rows);
        when(rows.list()).thenReturn(List.of(11L), List.of());
        assertEquals(List.of(11L), svc.replaceTomLinks(6L, List.of(11L), new CurrentUser(9L, "tester", "USER")));
        verify(template).update(contains("review_status = 'PENDING'"), any(), eq(6L));
        verify(template).update(contains("relation_type) VALUES (?, ?, 'EXPLAINS')"), eq(6L), eq(11L));
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> svc.replaceTomLinks(6L, List.of(11L), new CurrentUser(9L, "tester", "USER")));
    }

    private final TimeProvider timeProvider = mock(TimeProvider.class);
    {
        when(timeProvider.now()).thenReturn(LocalDateTime.of(2025, 1, 1, 12, 0));
    }

    @Test
    void activeContextCountsOnlyVisibleReviewedEntriesAndEscapesSearchTerm() {
        var jdbc = mock(JdbcClient.class);
        var svc = new WikiService(jdbc, mock(JdbcTemplate.class), timeProvider);
        var counts = mock(JdbcClient.StatementSpec.class);
        var matches = mock(JdbcClient.StatementSpec.class);
        var samples = mock(JdbcClient.StatementSpec.class);
        var countRows = mock(JdbcClient.MappedQuerySpec.class);
        var matchValue = mock(JdbcClient.MappedQuerySpec.class);
        var sampleRows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(startsWith("SELECT wp.scope"))).thenReturn(counts);
        when(jdbc.sql(startsWith("SELECT COUNT(*)"))).thenReturn(matches);
        when(jdbc.sql(startsWith("SELECT we.id"))).thenReturn(samples);
        when(counts.param("projectId", 10L)).thenReturn(counts);
        when(matches.param("projectId", 10L)).thenReturn(matches);
        when(samples.param("projectId", 10L)).thenReturn(samples);
        when(matches.param("pattern", "%访客!_%")).thenReturn(matches);
        when(samples.param("pattern", "%访客!_%")).thenReturn(samples);
        when(samples.param("limit", 8)).thenReturn(samples);
        when(counts.query(any(RowMapper.class))).thenReturn(countRows);
        when(samples.query(any(RowMapper.class))).thenReturn(sampleRows);
        when(matches.query(Long.class)).thenReturn(matchValue);
        when(countRows.list()).thenReturn(List.of(java.util.Map.entry("SYSTEM", 2L)));
        when(matchValue.single()).thenReturn(1L);
        when(sampleRows.list()).thenReturn(List.of());

        var result = svc.activeContext(10L, "访客_", 8);
        assertEquals(2L, ((java.util.Map<?, ?>) result.get("availableByScope")).get("SYSTEM"));
        assertEquals(1L, result.get("matchedCount"));
        assertEquals(true, result.get("truncated"));
        assertEquals("HISTORICAL_COVERAGE_ONLY", WikiService.evidenceRole("HISTORY", "IMPORTED_HISTORICAL_CASE"));
        assertEquals("SEMANTIC_KNOWLEDGE", WikiService.evidenceRole("RULE", "MANUAL"));
        verify(jdbc).sql(argThat((String sql) -> sql.contains("ORDER BY CASE WHEN we.entry_type = 'HISTORY'")
                && sql.contains("we.source_type")));
        verify(jdbc, times(3)).sql(argThat((String sql) -> sql.contains("wp.status = 'ACTIVE'")
                && sql.contains("wp.review_status = 'APPROVED'")
                && sql.contains("we.review_status = 'APPROVED'")
                && sql.contains("we.effective_status = 'ACTIVE'")
                && sql.contains("wp.project_id = :projectId OR wp.scope IN ('REUSABLE', 'SYSTEM')")));
    }

    @Test
    void activeEntryReturnsFullTextOnlyThroughReviewedVisibleQuery() throws Exception {
        var jdbc = mock(JdbcClient.class);
        var svc = new WikiService(jdbc, mock(JdbcTemplate.class), timeProvider);
        var spec = mock(JdbcClient.StatementSpec.class);
        var rows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(startsWith("SELECT we.id"))).thenReturn(spec);
        when(spec.param("entryId", 7L)).thenReturn(spec);
        when(spec.param("projectId", 10L)).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(rows);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("entryId", 7L);
        result.put("content", "完整的规则正文".repeat(100));
        when(rows.list()).thenReturn(List.of(result), List.of());

        assertEquals("完整的规则正文".repeat(100), svc.activeEntry(10L, 7L).get("content"));
        assertThrows(com.company.aitest.common.BusinessException.class, () -> svc.activeEntry(10L, 7L));
        assertThrows(com.company.aitest.common.BusinessException.class, () -> svc.activeEntry(10L, 0L));
        verify(jdbc, times(2)).sql(argThat((String sql) -> sql.contains("wp.status = 'ACTIVE'")
                && sql.contains("wp.review_status = 'APPROVED'")
                && sql.contains("we.review_status = 'APPROVED'")
                && sql.contains("we.effective_status = 'ACTIVE'")
                && sql.contains("wp.project_id = :projectId OR wp.scope IN ('REUSABLE', 'SYSTEM')")));
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
        when(mapped.list()).thenReturn(List.of(pack(1L, 10L, "PROJECT", "P1")));

        var result = svc.listPacks(10L);
        assertEquals(1, result.size());
        assertEquals("PROJECT", result.get(0).scope());
        verify(jdbc).sql(contains("scope = 'PROJECT'"));
    }

    @Test
    void submitPromotionCopiesOnlyReviewedEntriesIntoPendingCandidate() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = spy(new WikiService(jdbc, tmpl, timeProvider));
        var source = new WikiPackRecord(1L, 10L, "PROJECT", "访客规则", "ACTIVE", "APPROVED",
                null, null, null, "项目规则", 1L, LocalDateTime.now(), LocalDateTime.now());
        var candidate = new WikiPackRecord(5L, 10L, "REUSABLE", "访客规则（复用候选 1-1）",
                "DRAFT", "PENDING", null, "WIKI_PROMOTION", 1L, "项目规则", 9L,
                LocalDateTime.now(), LocalDateTime.now());
        doReturn(source).when(svc).getPack(1L);
        doReturn(candidate).when(svc).getPack(5L);

        var lock = mock(JdbcClient.StatementSpec.class);
        var countEntries = mock(JdbcClient.StatementSpec.class);
        var existing = mock(JdbcClient.StatementSpec.class);
        var countPrevious = mock(JdbcClient.StatementSpec.class);
        var lastId = mock(JdbcClient.StatementSpec.class);
        var lockValue = mock(JdbcClient.MappedQuerySpec.class);
        var countValue = mock(JdbcClient.MappedQuerySpec.class);
        var previousValue = mock(JdbcClient.MappedQuerySpec.class);
        var candidateValue = mock(JdbcClient.MappedQuerySpec.class);
        var idValue = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(startsWith("SELECT id FROM wiki_pack"))).thenReturn(lock);
        when(jdbc.sql(startsWith("SELECT COUNT(*) FROM wiki_entry"))).thenReturn(countEntries);
        when(jdbc.sql(startsWith("SELECT * FROM wiki_pack WHERE promotion_source_pack_id"))).thenReturn(existing);
        when(jdbc.sql(startsWith("SELECT COUNT(*) FROM wiki_pack WHERE promotion_source_pack_id"))).thenReturn(countPrevious);
        when(jdbc.sql("SELECT LAST_INSERT_ID()")).thenReturn(lastId);
        when(lock.param(1L)).thenReturn(lock);
        when(lock.query(Long.class)).thenReturn(lockValue);
        when(lockValue.optional()).thenReturn(java.util.Optional.of(1L));
        when(countEntries.param(1L)).thenReturn(countEntries);
        when(countEntries.query(Integer.class)).thenReturn(countValue);
        when(countValue.single()).thenReturn(2);
        when(existing.param("sourcePackId", 1L)).thenReturn(existing);
        when(existing.param("targetScope", "REUSABLE")).thenReturn(existing);
        when(existing.query(any(RowMapper.class))).thenReturn(candidateValue);
        when(candidateValue.optional()).thenReturn(java.util.Optional.empty());
        when(countPrevious.param("sourcePackId", 1L)).thenReturn(countPrevious);
        when(countPrevious.param("targetScope", "REUSABLE")).thenReturn(countPrevious);
        when(countPrevious.query(Integer.class)).thenReturn(previousValue);
        when(previousValue.single()).thenReturn(0);
        when(lastId.query(Long.class)).thenReturn(idValue);
        when(idValue.single()).thenReturn(5L);

        var result = svc.submitPromotion(1L, "REUSABLE", new CurrentUser(9L, "manager", "USER"));
        assertEquals(5L, result.id());
        verify(tmpl).update(contains("INSERT INTO wiki_pack"), eq(10L), eq("REUSABLE"),
                contains("复用候选"), isNull(), eq(1L), eq("项目规则"), eq(9L), any(), any());
        verify(tmpl).update(argThat((String sql) -> sql.contains("INSERT INTO wiki_entry")
                && sql.contains("'PENDING'") && sql.contains("'INACTIVE'")
                && sql.contains("source_type, source_ref_type, source_ref_id")
                && sql.contains("review_status = 'APPROVED'")),
                eq(5L), eq(9L), any(), any(), eq(1L));
    }

    @Test
    void promotionRejectsSkippingTheReusableReviewLevel() {
        var jdbc = mock(JdbcClient.class);
        var svc = spy(new WikiService(jdbc, mock(JdbcTemplate.class), timeProvider));
        var source = new WikiPackRecord(1L, 10L, "PROJECT", "访客规则", "ACTIVE", "APPROVED",
                null, null, null, null, 1L, LocalDateTime.now(), LocalDateTime.now());
        doReturn(source).when(svc).getPack(1L);
        var lock = mock(JdbcClient.StatementSpec.class);
        var value = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(startsWith("SELECT id FROM wiki_pack"))).thenReturn(lock);
        when(lock.param(1L)).thenReturn(lock);
        when(lock.query(Long.class)).thenReturn(value);
        when(value.optional()).thenReturn(java.util.Optional.of(1L));
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> svc.submitPromotion(1L, "SYSTEM", new CurrentUser(9L, "manager", "USER")));
    }

    @Test
    void listPacksForAdmin_appliesScopeAndReviewFiltersWithBoundParameters() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = new WikiService(jdbc, tmpl, timeProvider);
        when(tmpl.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        svc.listPacksForAdmin(10L, "REUSABLE", "ACTIVE", "APPROVED");

        verify(tmpl).query(argThat((String sql) -> sql.contains("project_id = ?")
                        && sql.contains("scope = ?")
                        && sql.contains("status = ?")
                        && sql.contains("review_status = ?")
                        && sql.contains("updated_at DESC")),
                any(RowMapper.class),
                eq(10L), eq("REUSABLE"), eq("ACTIVE"), eq("APPROVED"));
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
        when(tmpl.update(anyString(), any(Object[].class))).thenReturn(1);

        var user = new CurrentUser(1L, "admin", null);
        var result = svc.reviewEntry(1L, "APPROVED", user);

        verify(tmpl, atLeastOnce()).update(contains("effective_status"), any(Object[].class));
        verify(tmpl).update(contains("previous.id <> current.id"), any(Object[].class));
        assertEquals("APPROVED", result.reviewStatus());
    }

    @Test
    void editEntry_preservesTraceabilityAndRequiresFreshReview() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = new WikiService(jdbc, tmpl, timeProvider);

        var spec = mock(JdbcClient.StatementSpec.class);
        var mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("SELECT * FROM wiki_entry"))).thenReturn(spec);
        when(spec.param(eq(1L))).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of(entry(1L, 1L, "RULE", "Edited rule")));
        when(tmpl.update(anyString(), any(Object[].class))).thenReturn(1);

        var updated = svc.updateEntry(1L, "RULE", "Edited rule", "edited content", new CurrentUser(1L, "editor", null));

        verify(tmpl).update(argThat(sql -> sql.contains("review_status = 'PENDING'")
                        && sql.contains("effective_status = 'INACTIVE'")
                        && !sql.contains("source_refs_json")),
                eq("RULE"), eq("Edited rule"), eq("edited content"), any(), eq(1L));
        assertEquals("Edited rule", updated.title());
    }

    @Test
    void editPack_returnsItToDraftAndPendingReview() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = spy(new WikiService(jdbc, tmpl, timeProvider));
        doReturn(pack(1L, 10L, "PROJECT", "Edited pack")).when(svc).getPack(1L);
        when(tmpl.update(anyString(), any(Object[].class))).thenReturn(1);

        var updated = svc.updatePackMetadata(1L, "Edited pack", "edited description", new CurrentUser(1L, "editor", null));

        verify(tmpl).update(argThat(sql -> sql.contains("review_status = 'PENDING'") && sql.contains("status = 'DRAFT'")),
                eq("Edited pack"), eq("edited description"), any(), eq(1L));
        assertEquals("Edited pack", updated.name());
    }

    @Test
    void rejectPack_deactivatesAllEntries() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = spy(new WikiService(jdbc, tmpl, timeProvider));
        doReturn(pack(1L, 10L, "PROJECT", "TestPack")).when(svc).getPack(1L);
        when(tmpl.update(anyString(), any(Object[].class))).thenReturn(1);

        svc.reviewPack(1L, "REJECTED", new CurrentUser(2L, "reviewer", null));

        verify(tmpl).update(contains("UPDATE wiki_entry SET effective_status = 'INACTIVE'"), any(Object[].class));
    }

    @Test
    void activatePack_requiresApprovedPack() {
        var jdbc = mock(JdbcClient.class);
        var tmpl = mock(JdbcTemplate.class);
        var svc = spy(new WikiService(jdbc, tmpl, timeProvider));
        doReturn(pack(1L, 10L, "PROJECT", "TestPack")).when(svc).getPack(1L);

        var error = assertThrows(com.company.aitest.common.BusinessException.class,
                () -> svc.updatePackStatus(1L, "ACTIVE", new CurrentUser(1L, "admin", null)));
        assertTrue(error.getMessage().contains("审核通过"));
        verify(tmpl, never()).update(contains("UPDATE wiki_pack SET status"), any(Object[].class));
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
        return new WikiPackRecord(id, projectId, scope, name, "DRAFT", "PENDING", null, null, null, null, 1L, LocalDateTime.now(), LocalDateTime.now());
    }

    private WikiEntryRecord entry(Long id, Long packId, String type, String title) {
        return new WikiEntryRecord(id, packId, type, title, "content", null, null, "PENDING", null, "ACTIVE", 1L, LocalDateTime.now(), LocalDateTime.now());
    }
}
