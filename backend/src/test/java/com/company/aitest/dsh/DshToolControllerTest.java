package com.company.aitest.dsh;

import java.util.List;
import java.util.Map;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TomUsageMode;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.minitom.TestObjectModelRecord;
import com.company.aitest.project.ProjectAccessService;
import com.company.aitest.project.ProjectService;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.company.aitest.llm.gateway.retrieval.RagRetrievalService;
import com.company.aitest.wiki.WikiService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DshToolControllerTest {
    @Test
    void semanticContextUsesTheSelectedTomMode() {
        var semantic = mock(ProjectSemanticContextService.class);
        var access = mock(ProjectAccessService.class);
        var projects = mock(ProjectService.class);
        var user = new CurrentUser(9L, "tester", "USER");
        var auth = new UsernamePasswordAuthenticationToken(new DshConnectionService.DshPrincipal(3L, user), null);
        var controller = new DshToolController(projects, access, semantic, mock(RagRetrievalService.class),
                mock(DshDraftService.class), mock(DshPreferenceService.class),
                mock(DshWorkSessionService.class), mock(JdbcClient.class), mock(MiniTomService.class),
                mock(WikiService.class));
        when(projects.get(2L)).thenReturn(mock(com.company.aitest.project.ProjectRecord.class));
        when(semantic.build(2L, "访客审批", List.of(), 10, TomUsageMode.DIRECT))
                .thenReturn(new ProjectSemanticContextService.BuildResult("", List.of()));
        controller.context(2L, "访客审批", "DIRECT", auth);
        verify(semantic).build(2L, "访客审批", List.of(), 10, TomUsageMode.DIRECT);
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> controller.context(2L, "访客审批", "BAD", auth));
    }
    @Test
    void detailRoutesRequireProjectAccessAndReturnOnlyVisibleFullRecords() {
        var access = mock(ProjectAccessService.class);
        var wiki = mock(WikiService.class);
        var jdbc = mock(JdbcClient.class);
        var user = new CurrentUser(9L, "tester", "USER");
        var auth = new UsernamePasswordAuthenticationToken(new DshConnectionService.DshPrincipal(3L, user), null);
        var controller = new DshToolController(mock(ProjectService.class), access,
                mock(ProjectSemanticContextService.class), mock(RagRetrievalService.class),
                mock(DshDraftService.class), mock(DshPreferenceService.class),
                mock(DshWorkSessionService.class), jdbc, mock(MiniTomService.class), wiki);
        when(wiki.activeEntry(2L, 7L)).thenReturn(Map.of("entryId", 7L, "content", "规则正文"));
        assertEquals("规则正文", ((Map<?, ?>) controller.wikiEntry(2L, 7L, auth).data()).get("content"));

        var spec = mock(JdbcClient.StatementSpec.class);
        var rows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(startsWith("SELECT id, case_no"))).thenReturn(spec);
        when(spec.param("caseId", 11L)).thenReturn(spec);
        when(spec.param("projectId", 2L)).thenReturn(spec);
        when(spec.param("userId", 9L)).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(rows);
        when(rows.list()).thenReturn(List.of(Map.of("id", 11L, "steps", "1. 点击新增", "expectedResult", "1. 显示弹窗")), List.of());
        assertEquals("1. 点击新增", ((Map<?, ?>) controller.caseDetail(2L, 11L, auth).data()).get("steps"));
        assertThrows(com.company.aitest.common.BusinessException.class, () -> controller.caseDetail(2L, 11L, auth));
        verify(jdbc, times(2)).sql(argThat((String sql) -> sql.contains("case_status = 'SUBMITTED'")
                && sql.contains("deprecated_at IS NULL") && sql.contains("submitted_by = :userId")));

        doThrow(new com.company.aitest.common.BusinessException("无权限"))
                .when(access).ensureCanAccess(3L, user);
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> controller.wikiEntry(3L, 7L, auth));
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> controller.caseDetail(3L, 11L, auth));
        verify(wiki, never()).activeEntry(eq(3L), anyLong());
    }
    @Test
    void wikiContextRequiresProjectAccessAndUsesReviewedWikiService() {
        var access = mock(ProjectAccessService.class);
        var wiki = mock(WikiService.class);
        var user = new CurrentUser(9L, "tester", "USER");
        var auth = new UsernamePasswordAuthenticationToken(new DshConnectionService.DshPrincipal(3L, user), null);
        var controller = new DshToolController(mock(ProjectService.class), access,
                mock(ProjectSemanticContextService.class), mock(RagRetrievalService.class),
                mock(DshDraftService.class), mock(DshPreferenceService.class),
                mock(DshWorkSessionService.class), mock(JdbcClient.class), mock(MiniTomService.class), wiki);
        when(wiki.activeContext(2L, "访客", 8)).thenReturn(Map.of("matchedCount", 1));
        assertEquals(Map.of("matchedCount", 1), controller.wikiContext(2L, "访客", 8, auth).data());
        verify(access).ensureCanAccess(2L, user);
        doThrow(new com.company.aitest.common.BusinessException("无权限"))
                .when(access).ensureCanAccess(3L, user);
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> controller.wikiContext(3L, "访客", 8, auth));
        verify(wiki, never()).activeContext(eq(3L), anyString(), anyInt());
    }

    @Test
    void historicalStyleExamplesRequireConcreteStepsAndPreferCurrentProject() {
        var access = mock(ProjectAccessService.class);
        var miniTom = mock(MiniTomService.class);
        var jdbc = mock(JdbcClient.class);
        var user = new CurrentUser(9L, "tester", "USER");
        var auth = new UsernamePasswordAuthenticationToken(new DshConnectionService.DshPrincipal(3L, user), null);
        when(miniTom.listActive(2L, null, user)).thenReturn(List.of());
        var spec = mock(JdbcClient.StatementSpec.class);
        var rows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("FROM historical_case_import_item"))).thenReturn(spec);
        when(spec.param("sourceProjectId", 2L)).thenReturn(spec);
        when(spec.param("limit", 8)).thenReturn(spec);
        when(spec.param("focus", "邀请记录")).thenReturn(spec);
        when(spec.param("pattern", "%邀请记录%")).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(rows);
        when(rows.list()).thenReturn(List.of());
        var controller = new DshToolController(mock(ProjectService.class), access,
                mock(ProjectSemanticContextService.class), mock(RagRetrievalService.class),
                mock(DshDraftService.class), mock(DshPreferenceService.class),
                mock(DshWorkSessionService.class), jdbc, miniTom, mock(WikiService.class));
        assertEquals(List.of(), controller.historicalStyleExamples(2L, 8, "邀请记录", auth).data());
        verify(jdbc).sql(argThat((String sql) -> sql.contains("CHAR_LENGTH(TRIM(COALESCE(i.steps, ''))) >= 12")
                && sql.contains("CHAR_LENGTH(TRIM(COALESCE(i.expected_result, ''))) >= 12")));
    }

    @Test
    void tomModeFiltersActiveScopesAndDesignRulesComeFromOriginalPolicy() {
        var access = mock(ProjectAccessService.class);
        var miniTom = mock(MiniTomService.class);
        var user = new CurrentUser(9L, "tester", "USER");
        var auth = new UsernamePasswordAuthenticationToken(new DshConnectionService.DshPrincipal(3L, user), null);
        var projectTom = mock(TestObjectModelRecord.class);
        var systemTom = mock(TestObjectModelRecord.class);
        when(projectTom.scope()).thenReturn("PROJECT");
        when(systemTom.scope()).thenReturn("SYSTEM");
        when(miniTom.listActive(2L, null, user)).thenReturn(List.of(projectTom, systemTom));
        var controller = new DshToolController(mock(ProjectService.class), access,
                mock(ProjectSemanticContextService.class), mock(RagRetrievalService.class),
                mock(DshDraftService.class), mock(DshPreferenceService.class),
                mock(DshWorkSessionService.class), mock(JdbcClient.class), miniTom,
                mock(WikiService.class));

        assertEquals(List.of(), controller.tom(2L, "DIRECT", auth).data());
        assertEquals(List.of(projectTom), controller.tom(2L, "PROJECT_TOM", auth).data());
        assertEquals(List.of(projectTom, systemTom), controller.tom(2L, "PROJECT_AND_SYSTEM_TOM", auth).data());
        verify(access, times(3)).ensureCanAccess(2L, user);
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> controller.tom(2L, "UNKNOWN", auth));

        @SuppressWarnings("unchecked")
        var rules = (List<Map<String, Object>>) controller.designRules().data();
        assertTrue(rules.stream().anyMatch(rule -> "BOUNDARY".equals(rule.get("pointType"))
                && ((List<?>) rule.get("coverageRequirements")).contains("JUST_OUTSIDE_BOUNDARY")));
        @SuppressWarnings("unchecked")
        var writing = (Map<String, Object>) controller.caseWritingStandard().data();
        assertEquals(7, ((List<?>) writing.get("columns")).size());
        assertTrue(((String) writing.get("stepRule")).contains("只有一步"));
    }
}
