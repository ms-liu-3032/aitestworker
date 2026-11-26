package com.company.aitest.businesspack;

import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BusinessPackControllerAccessTest {
    private final BusinessPackService packs = mock(BusinessPackService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final BusinessPackAccessService guard = new BusinessPackAccessService(access, jdbc);
    private final BusinessPackController controller = new BusinessPackController(packs, guard);
    private final CurrentUser user = new CurrentUser(9L, "tester", "USER");
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null);

    @Test
    void mvcInvokesAuthorizationForLegacyListRoute() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/projects/3/business-packs").principal(auth))
                .andExpect(status().isOk());
        verify(access).ensureCanAccess(3L, user);
    }

    @Test
    void outsiderCannotReadProjectPacks() {
        doThrow(new BusinessException("无权访问当前项目")).when(access).ensureCanAccess(3L, user);
        assertThrows(BusinessException.class, () -> controller.authorize(3L, Map.of(), auth, request("GET")));
        verifyNoInteractions(packs, jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void foreignPackIdIsRejectedBeforeHandlerRuns() {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Integer> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Integer.class)).thenReturn(query);
        when(query.single()).thenReturn(0);
        assertThrows(BusinessException.class, () -> controller.authorize(3L, Map.of("packId", "77"), auth, request("GET")));
        verifyNoInteractions(packs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mvcRejectsForeignPackIdBeforeHandlerRuns() {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Integer> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(Integer.class)).thenReturn(query);
        when(query.single()).thenReturn(0);
        assertThrows(Exception.class, () -> MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/projects/3/business-packs/77").principal(auth)));
        verifyNoInteractions(packs);
    }

    @Test
    void ordinaryMemberCannotMutateProjectPacks() {
        doThrow(new BusinessException("无权管理当前项目")).when(access).ensureCanManageProject(3L, user);
        assertThrows(BusinessException.class, () -> controller.authorize(3L, Map.of(), auth, request("POST")));
        verifyNoInteractions(packs, jdbc);
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/api/projects/3/business-packs");
    }
}
