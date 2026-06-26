package com.company.aitest.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class LoopControllerTest {

    private final LoopService loopService = mock(LoopService.class);
    private final LoopController controller = new LoopController(loopService);

    private Authentication auth() {
        var user = new CurrentUser(1L, "admin", null);
        return new UsernamePasswordAuthenticationToken(user, null);
    }

    @Test
    void getStatus_delegatesToService() {
        when(loopService.isLoopEnabled()).thenReturn(true);
        var resp = controller.getStatus();
        assertEquals(true, resp.data());
    }

    @Test
    void setStatus_callsService() {
        when(loopService.isLoopEnabled()).thenReturn(false);
        controller.setStatus(Map.of("enabled", true), auth());
        verify(loopService).setLoopEnabled(eq(true), any(CurrentUser.class));
    }

    @Test
    void listEvents_delegatesToService() {
        when(loopService.listEvents(10L)).thenReturn(List.of());
        var resp = controller.listEvents(10L);
        assertNotNull(resp.data());
    }

    @Test
    void listClusters_delegatesToService() {
        when(loopService.listClusters(10L)).thenReturn(List.of());
        var resp = controller.listClusters(10L);
        assertNotNull(resp.data());
    }

    @Test
    void updateClusterStatus_callsService() {
        var cluster = new LoopClusterRecord(1L, 10L, "theme", 5, "action", "WIKI", "APPROVED", LocalDateTime.now(), LocalDateTime.now());
        when(loopService.updateClusterStatus(1L, "APPROVED")).thenReturn(cluster);

        var resp = controller.updateClusterStatus(1L, Map.of("status", "APPROVED"));
        assertEquals("APPROVED", resp.data().status());
    }

    @Test
    void updateEventStatus_callsService() {
        var event = new LoopEventRecord(1L, 10L, "TOM_STRATEGY", "ANALYSIS", "raw", "issue", null, null, "CONSUMED", 1L, LocalDateTime.now(), LocalDateTime.now());
        when(loopService.updateEventStatus(1L, "CONSUMED")).thenReturn(event);

        var resp = controller.updateEventStatus(1L, Map.of("status", "CONSUMED"));
        assertEquals("CONSUMED", resp.data().status());
    }

    @Test
    void consume_callsGenerateCandidates() {
        when(loopService.generateCandidates(10L)).thenReturn(3);
        var resp = controller.consume(10L, auth());
        assertEquals(3, resp.data().get("candidatesGenerated"));
    }
}
