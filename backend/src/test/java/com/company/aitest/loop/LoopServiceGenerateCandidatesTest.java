package com.company.aitest.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.company.aitest.knowledge.KnowledgeDepositionService;

class LoopServiceGenerateCandidatesTest {

    @Test
    void generateCandidates_emptyClusters_returnsZero() {
        var ctx = new TestContext();
        var clusterSpec = mock(JdbcClient.StatementSpec.class);
        var clusterMapped = mock(JdbcClient.MappedQuerySpec.class);
        when(clusterSpec.param(any())).thenReturn(clusterSpec);
        when(clusterSpec.query(any(RowMapper.class))).thenReturn(clusterMapped);
        when(clusterMapped.list()).thenReturn(List.of());
        when(ctx.jdbc.sql(anyString())).thenReturn(clusterSpec);
        var svc = new LoopService(ctx.jdbc, ctx.tmpl, ctx.tp);
        assertEquals(0, svc.generateCandidates(10L));
    }

    @Test
    void generateCandidates_wikiType_insertsWikiPackAndEntry() {
        var ctx = new TestContext();
        ctx.mockCycle("WIKI", "GENERATION_QUALITY");
        var svc = new LoopService(ctx.jdbc, ctx.tmpl, ctx.tp);
        assertEquals(1, svc.generateCandidates(10L));
        assertTrue(ctx.hasSql("INSERT INTO wiki_pack"), "Should insert wiki_pack");
        assertTrue(ctx.hasSql("INSERT INTO wiki_entry"), "Should insert wiki_entry");
        assertFalse(ctx.hasSql("INSERT INTO test_object_model"), "WIKI should NOT insert TOM");
    }

    @Test
    void generateCandidates_tomType_insertsTomCandidate() {
        var ctx = new TestContext();
        ctx.mockCycle("TOM", "TOM_STRATEGY");
        var svc = new LoopService(ctx.jdbc, ctx.tmpl, ctx.tp);
        assertEquals(1, svc.generateCandidates(10L));
        assertTrue(ctx.hasSql("INSERT INTO test_object_model"), "TOM should insert test_object_model");
        assertFalse(ctx.hasSql("INSERT INTO wiki_pack"), "TOM should NOT insert wiki_pack");
    }

    @Test
    void generateCandidates_tomType_writesStructuredSourceRefs() {
        var ctx = new TestContext();
        ctx.mockCycle("TOM", "TOM_STRATEGY");
        var svc = new LoopService(ctx.jdbc, ctx.tmpl, ctx.tp);
        svc.generateCandidates(10L);

        assertTrue(ctx.hasSql("source_refs_json"), "TOM INSERT SQL should contain source_refs_json column");
        assertTrue(ctx.hasSql("LOOP_CANDIDATE"), "TOM INSERT SQL should contain LOOP_CANDIDATE source_type");
        String sourceRefsJson = ctx.firstTomSourceRefsJson();
        assertNotNull(sourceRefsJson, "TOM INSERT should pass structured source_refs_json parameter");
        assertTrue(sourceRefsJson.contains("\"sourceType\":\"LOOP_CANDIDATE\""));
        assertTrue(sourceRefsJson.contains("\"clusterId\":1"));
        assertTrue(sourceRefsJson.contains("\"eventIds\":[1]"));
        assertTrue(sourceRefsJson.contains("\"eventType\":\"TOM_STRATEGY\""));
        assertTrue(sourceRefsJson.contains("\"sourceStage\":\"ANALYSIS\""));
        assertTrue(sourceRefsJson.contains("\"normalizedIssue\":\"normalized issue 1\""));
    }

    @Test
    void generateCandidates_wikiType_writesSourceRefsJson() {
        var ctx = new TestContext();
        ctx.mockCycle("WIKI", "GENERATION_QUALITY");
        var svc = new LoopService(ctx.jdbc, ctx.tmpl, ctx.tp);
        svc.generateCandidates(10L);

        assertTrue(ctx.hasSql("source_refs_json"), "wiki_entry INSERT should contain source_refs_json column");
    }

    @Test
    void generateCandidates_wikiType_usesUnifiedDepositionServiceWhenAvailable() {
        var ctx = new TestContext();
        ctx.mockCycle("WIKI", "GENERATION_QUALITY");
        var deposition = mock(KnowledgeDepositionService.class);
        when(deposition.depositLoopWikiCandidate(anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new KnowledgeDepositionService.DepositionResult(1, 0, 0));
        var svc = new LoopService(ctx.jdbc, ctx.tmpl, ctx.tp);
        svc.setKnowledgeDepositionService(deposition);

        assertEquals(1, svc.generateCandidates(10L));

        verify(deposition).depositLoopWikiCandidate(eq(10L), eq(1L), eq(1L),
                eq("GENERATION_QUALITY"), eq("ANALYSIS"), contains("回灌建议"),
                contains("normalized issue 1"), eq(1L));
        assertFalse(ctx.hasSql("INSERT INTO wiki_entry"));
    }

    @Test
    void generateCandidates_multipleEvents_writesMultipleTomEntries() {
        var ctx = new TestContext();
        ctx.mockCycleWithEvents("TOM", "TOM_STRATEGY", 3);
        var svc = new LoopService(ctx.jdbc, ctx.tmpl, ctx.tp);
        assertEquals(3, svc.generateCandidates(10L));
        assertEquals(3, ctx.tomSourceRefsJsons().size(), "Each TOM event should generate one structured source_refs_json payload");
        assertTrue(ctx.tomSourceRefsJsons().get(2).contains("\"eventIds\":[3]"));
    }

    private static class TestContext {
        final JdbcTemplate tmpl = mock(JdbcTemplate.class);
        final JdbcClient jdbc = mock(JdbcClient.class);
        final com.company.aitest.common.TimeProvider tp = mock(com.company.aitest.common.TimeProvider.class);
        final ArrayList<String> capturedSqls = new ArrayList<>();
        final ArrayList<CapturedUpdate> capturedUpdates = new ArrayList<>();

        TestContext() {
            when(tp.now()).thenReturn(LocalDateTime.of(2025, 6, 25, 12, 0));
        }

        boolean hasSql(String fragment) {
            return capturedSqls.stream().anyMatch(s -> s.contains(fragment));
        }

        String firstTomSourceRefsJson() {
            return tomSourceRefsJsons().stream().findFirst().orElse(null);
        }

        List<String> tomSourceRefsJsons() {
            return capturedUpdates.stream()
                    .filter(update -> update.sql().contains("INSERT INTO test_object_model"))
                    .map(update -> (String) update.params().get(3))
                    .toList();
        }

        void mockCycle(String assetType, String eventType) {
            mockCycleWithEvents(assetType, eventType, 1);
        }

        void mockCycleWithEvents(String assetType, String eventType, int eventCount) {
            var clusterSpec = mock(JdbcClient.StatementSpec.class);
            var clusterMapped = mock(JdbcClient.MappedQuerySpec.class);
            var eventSpec = mock(JdbcClient.StatementSpec.class);
            var eventMapped = mock(JdbcClient.MappedQuerySpec.class);
            var idSpec = mock(JdbcClient.StatementSpec.class);
            var idMapped = mock(JdbcClient.MappedQuerySpec.class);

            when(clusterSpec.param(any())).thenReturn(clusterSpec);
            when(clusterSpec.query(any(RowMapper.class))).thenReturn(clusterMapped);
            when(clusterMapped.list()).thenReturn(List.of(
                    new LoopClusterRecord(1L, 10L, "THEME", 3, "fix", assetType, "APPROVED", LocalDateTime.now(), LocalDateTime.now())
            ));

            when(eventSpec.param(any())).thenReturn(eventSpec);
            when(eventSpec.query(any(RowMapper.class))).thenReturn(eventMapped);
            var events = new ArrayList<LoopEventRecord>();
            for (int i = 1; i <= eventCount; i++) {
                events.add(new LoopEventRecord((long) i, 10L, eventType, "ANALYSIS",
                        "raw input " + i, "normalized issue " + i, null, null, "PENDING", 1L,
                        LocalDateTime.now(), LocalDateTime.now()));
            }
            when(eventMapped.list()).thenReturn(events);

            when(idSpec.param(any())).thenReturn(idSpec);
            when(idSpec.query(Long.class)).thenReturn(idMapped);
            when(idMapped.single()).thenReturn(99L);

            when(jdbc.sql(anyString())).thenAnswer(inv -> {
                String sql = inv.getArgument(0, String.class);
                if (sql.contains("learning_loop_event")) return eventSpec;
                if (sql.contains("wiki_pack WHERE")) return idSpec;
                return clusterSpec;
            });

            lenient().doAnswer(inv -> {
                String sql = inv.getArgument(0, String.class);
                Object[] args = inv.getArguments();
                List<Object> params = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    params.add(args[i]);
                }
                capturedSqls.add(sql);
                capturedUpdates.add(new CapturedUpdate(sql, params));
                return 1;
            }).when(tmpl).update(any(String.class), any(Object[].class));
        }
    }

    private record CapturedUpdate(String sql, List<Object> params) {}
}
