package com.company.aitest.dsh;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DshCredentialFilter extends OncePerRequestFilter {
    private final DshConnectionService connections;

    public DshCredentialFilter(DshConnectionService connections) {
        this.connections = connections;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/dsh/tools/")
                && !request.getRequestURI().startsWith("/api/dsh/user/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // A normal Web JWT may authenticate the request, but never grants DSH tool access.
        SecurityContextHolder.clearContext();
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            boolean userSession = request.getRequestURI().startsWith("/api/dsh/user/");
            DshConnectionService.DshPrincipal principal = connections.authenticate(header.substring(7), userSession);
            if (principal != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_DSH_AGENT"))));
            }
        }
        chain.doFilter(request, response);
    }
}
