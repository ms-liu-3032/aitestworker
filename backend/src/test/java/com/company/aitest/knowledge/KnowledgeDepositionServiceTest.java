package com.company.aitest.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class KnowledgeDepositionServiceTest {

    private JdbcClient jdbc;
    private JdbcTemplate template;
    private KnowledgeDepositionService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        template = mock(JdbcTemplate.class);
        TimeProvider timeProvider = mock(TimeProvider.class);
        when(timeProvider.now()).thenReturn(LocalDateTime.of(2026, 7, 21, 9, 0));

        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Long> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("SELECT id FROM wiki_pack"))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.query(Long.class)).thenReturn(mapped);
        when(mapped.single()).thenReturn(5L);
        when(template.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(template.update(anyString(), any(Object[].class))).thenReturn(1);

        service = new KnowledgeDepositionService(jdbc, template, timeProvider, new ObjectMapper());
    }

    @Test
    void confirmedRequirementCreatesWikiAndProjectTomCandidatesButSkipsExcludedAtoms() {
        String analysis = """
                {
                  "requirement_understanding":"实现审批与通知闭环",
                  "business_domain":"审批",
                  "requirement_atoms":[
                    {"id":"R1","category":"FLOW","title":"审批流程","requirement":"提交后进入审批","generation_scope":"GENERATE"},
                    {"id":"R2","category":"RULE","title":"历史背景","requirement":"旧系统说明","generation_scope":"EXCLUDED"}
                  ]
                }
                """;
        var result = service.depositConfirmedRequirement(3L, 11L, 2, analysis,
                "[{\"question\":\"超时怎么办？\"}]", "[{\"index\":0,\"answer\":\"自动驳回\"}]",
                new CurrentUser(7L, "tester", null));

        assertEquals(3, result.wikiCandidates());
        assertEquals(1, result.tomCandidates());
        verify(template, atLeastOnce()).update(contains("'PENDING', 0.70, 'INACTIVE'"), any(Object[].class));
        verify(template).update(contains("'CANDIDATE', 1, 'TO_CONFIRM'"), any(Object[].class));
    }

    @Test
    void confirmedTestPointsCreateAssertionTomAndGroupedWikiCandidates() {
        String points = """
                [
                  {"id":"TP1","module":"审批","title":"审批通过","description":"验证通过分支","generation_scope":"GENERATE"},
                  {"id":"TP2","module":"审批","title":"旧背景","description":"仅排除","generation_scope":"EXCLUDED"}
                ]
                """;
        var result = service.depositConfirmedTestPoints(3L, 11L, 2, points,
                new CurrentUser(7L, "tester", null));

        assertEquals(1, result.wikiCandidates());
        assertEquals(1, result.tomCandidates());
        verify(template).update(contains("INSERT INTO test_object_model"), any(Object[].class));
        verify(template, atLeastOnce()).update(contains("INSERT INTO wiki_entry"), any(Object[].class));
    }

    @Test
    void uploadedDocumentCreatesInactiveReviewableWikiCandidate() {
        var result = service.depositUploadedDocument(3L, "GENERATION_ATTACHMENT", 19L,
                "需求说明.pdf", "这是需要审核后才能生效的项目资料", 7L);

        assertEquals(1, result.wikiCandidates());
        assertEquals(0, result.tomCandidates());
        verify(template).update(contains("'PENDING', 0.70, 'INACTIVE'"), any(Object[].class));
    }

    @Test
    void sameRequirementAcrossAnalysisVersionsUsesStableCandidateKeys() {
        List<String> keys = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] args = invocation.getArguments();
            Object[] params = java.util.Arrays.copyOfRange(args, 1, args.length);
            if (sql.contains("INSERT INTO wiki_entry") && "审批流程".equals(params[2])) {
                keys.add(String.valueOf(params[5]));
            }
            return 1;
        }).when(template).update(anyString(), any(Object[].class));
        String analysis = """
                {"business_domain":"审批","requirement_atoms":[
                  {"id":"R1","category":"FLOW","title":"审批流程","requirement":"提交后进入审批","generation_scope":"GENERATE"}
                ]}
                """;

        service.depositConfirmedRequirement(3L, 11L, 1, analysis, "[]", "[]",
                new CurrentUser(7L, "tester", null));
        service.depositConfirmedRequirement(3L, 12L, 2, analysis, "[]", "[]",
                new CurrentUser(7L, "tester", null));

        assertEquals(2, keys.size());
        assertEquals(keys.get(0), keys.get(1));
    }
}
