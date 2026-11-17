package com.company.aitest.dsh;

import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import com.company.aitest.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshDesignContextControllerTest {
    @Test
    void boundUserMustAccessProjectBeforeBundleIsRead() {
        var access = mock(ProjectAccessService.class);
        var service = mock(DshDesignContextService.class);
        var projects = mock(ProjectService.class);
        var user = new CurrentUser(9L, "tester", "USER");
        var auth = new UsernamePasswordAuthenticationToken(new DshConnectionService.DshPrincipal(3L, user), null);
        var controller = new DshDesignContextController(access, service, projects);
        var request = new DshDesignContextController.Request("访客审批", "PROJECT_TOM");
        when(service.build(2L, "访客审批", "PROJECT_TOM", user)).thenReturn(Map.of("tomAvailableCount", 2));
        assertEquals(2, controller.designContext(2L, request, auth).data().get("tomAvailableCount"));
        verify(access).ensureCanAccess(2L, user);
        doThrow(new BusinessException("无权限")).when(access).ensureCanAccess(3L, user);
        assertThrows(BusinessException.class, () -> controller.designContext(3L, request, auth));
        verify(service, never()).build(eq(3L), anyString(), anyString(), any());
    }
}
