package com.company.aitest.dsh;

import com.company.aitest.common.CurrentUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshCredentialFilterTest {
    private final DshConnectionService connections = mock(DshConnectionService.class);
    private final DshCredentialFilter filter = new DshCredentialFilter(connections);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void toolRouteRejectsAnExistingWebAuthentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(1L, "web", "ADMIN"), null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dsh/tools/projects");
        request.addHeader("Authorization", "Bearer web-jwt");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(connections).authenticate("web-jwt", false);
        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void toolRouteAcceptsOnlyBoundDshPrincipal() throws Exception {
        var principal = new DshConnectionService.DshPrincipal(7L, new CurrentUser(3L, "tester", "USER"));
        when(connections.authenticate("dsh_agent_valid", false)).thenReturn(principal);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dsh/tools/projects");
        request.addHeader("Authorization", "Bearer dsh_agent_valid");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertEquals(principal, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void ordinaryWebRouteDoesNotUseDshCredential() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("Authorization", "Bearer dsh_agent_valid");
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        verifyNoInteractions(connections);
    }

    @Test
    void userRouteRejectsWebJwtAndAgentCredential() throws Exception {
        var principal = new DshConnectionService.DshPrincipal(7L, new CurrentUser(3L, "tester", "USER"));
        when(connections.authenticate("dsh_user_valid", true)).thenReturn(principal);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/dsh/user/projects/1/drafts/2/submit");
        request.addHeader("Authorization", "Bearer dsh_user_valid");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertEquals(principal, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        SecurityContextHolder.clearContext();
        request.removeHeader("Authorization");
        request.addHeader("Authorization", "Bearer web-jwt");
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        request.removeHeader("Authorization");
        request.addHeader("Authorization", "Bearer dsh_agent_valid");
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
