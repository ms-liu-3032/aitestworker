package com.company.aitest.dsh;

import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.company.aitest.wiki.WikiPackRecord;
import com.company.aitest.wiki.WikiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshAssetProposalControllerTest {
    private final JdbcTemplate template = mock(JdbcTemplate.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final DshWorkSessionService sessions = mock(DshWorkSessionService.class);
    private final WikiService wiki = mock(WikiService.class);
    private final CurrentUser user = new CurrentUser(9L, "tester", "USER");
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            new DshConnectionService.DshPrincipal(3L, user), null);
    private final DshAssetProposalController controller = new DshAssetProposalController(
            template, jdbc, access, sessions, wiki, mock(TimeProvider.class), new ObjectMapper());

    @Test
    void wikiProposalUsesBoundSessionAndCreatesOnlyProjectPendingAssets() {
        when(sessions.get(18L, 6L, user)).thenReturn(new DshWorkSessionService.WorkSession(
                6L, 18L, 7L, "访客", "PROJECT_TOM", "ACTIVE"));
        var statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked") var rows = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(any())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(rows);
        when(rows.list()).thenReturn(List.of());
        when(wiki.createPack(eq(18L), eq("PROJECT"), anyString(), contains("待人工审核"), eq(user)))
                .thenReturn(new WikiPackRecord(33L, 18L, "PROJECT", "候选", "DRAFT", "PENDING",
                        null, null, null, null, 9L, null, null));
        when(wiki.createEntry(eq(33L), eq("IMPLEMENTATION"), eq("邀请入口"), eq("邀请记录页面点击新增按钮"),
                isNull(), contains("workSessionId"), eq(user)))
                .thenReturn(new com.company.aitest.wiki.WikiEntryRecord(44L, 33L,
                        "IMPLEMENTATION", "邀请入口", "邀请记录页面点击新增按钮", null, null,
                        "PENDING", null, "INACTIVE", 9L, null, null));
        var response = controller.wiki(18L, new DshAssetProposalController.WikiRequest(6L, "实测模型",
                List.of(new DshAssetProposalController.WikiItem("IMPLEMENTATION", "邀请入口",
                        "邀请记录页面点击新增按钮", "项目 18 浏览器实测"))), auth);
        assertEquals("PENDING_REVIEW", response.data().get("status"));
        assertEquals(List.of(44L), response.data().get("entryIds"));
        verify(wiki).createPack(eq(18L), eq("PROJECT"), anyString(), anyString(), eq(user));
    }

    @Test
    void foreignOrClosedSessionCannotWriteAnyCandidate() {
        when(sessions.get(18L, 6L, user)).thenThrow(new BusinessException("无权访问"));
        var request = new DshAssetProposalController.WikiRequest(6L, "实测模型",
                List.of(new DshAssetProposalController.WikiItem("RULE", "规则", "内容足够十个字以上", "浏览器实测来源")));
        assertThrows(BusinessException.class, () -> controller.wiki(18L, request, auth));
        verifyNoInteractions(wiki, template);
    }

    @Test
    void boundAgentCanDiscoverDirectedRelationTypes() {
        var types = controller.relationTypes(18L, auth).data();
        assertTrue(types.stream().anyMatch(item -> "FLOW_CHANGES_STATE".equals(item.get("type"))
                && "FLOW".equals(item.get("fromType")) && "STATE".equals(item.get("toType"))));
        assertTrue(types.stream().anyMatch(item -> "STATE_TRANSITIONS_TO".equals(item.get("type"))));
        verify(access).ensureCanAccess(18L, user);
    }
}
