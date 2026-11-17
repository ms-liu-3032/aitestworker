package com.company.aitest.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;

import com.company.aitest.audit.OperationLogService;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class WikiControllerTest {

    @Test
    void projectPromotionRequiresProjectManagerAndSystemPromotionRequiresAdmin() {
        var service = mock(WikiService.class);
        var access = mock(ProjectAccessService.class);
        var controller = new WikiController(service, access, mock(OperationLogService.class));
        var user = new CurrentUser(9L, "manager", "USER");
        var auth = new UsernamePasswordAuthenticationToken(user, null);
        var source = new WikiPackRecord(1L, 10L, "PROJECT", "项目规则", "ACTIVE", "APPROVED",
                null, null, null, null, 9L, java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        var candidate = new WikiPackRecord(2L, 10L, "REUSABLE", "复用候选", "DRAFT", "PENDING",
                null, null, 1L, null, 9L, java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        when(service.getPack(1L)).thenReturn(source);
        when(service.submitPromotion(1L, "REUSABLE", user)).thenReturn(candidate);
        assertEquals(2L, controller.submitPromotion(1L, java.util.Map.of("targetScope", "REUSABLE"), auth).data().id());
        verify(access).ensureCanManageProject(10L, user);

        var reusable = new WikiPackRecord(3L, 10L, "REUSABLE", "复用规则", "ACTIVE", "APPROVED",
                null, null, 1L, null, 9L, java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        when(service.getPack(3L)).thenReturn(reusable);
        doThrow(new com.company.aitest.common.BusinessException("需要平台管理员"))
                .when(access).ensurePlatformAdmin(user);
        assertThrows(com.company.aitest.common.BusinessException.class,
                () -> controller.submitPromotion(3L, java.util.Map.of("targetScope", "SYSTEM"), auth));
        verify(service, never()).submitPromotion(eq(3L), anyString(), any());
    }

    @Test
    void adminCatalog_requiresPlatformAdminAndDelegatesFilters() {
        WikiService service = mock(WikiService.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        WikiController controller = new WikiController(service, access, mock(OperationLogService.class));
        CurrentUser user = new CurrentUser(1L, "admin", "ADMIN");
        var auth = new UsernamePasswordAuthenticationToken(user, null);
        when(service.listPacksForAdmin(7L, "SYSTEM", "ACTIVE", "APPROVED")).thenReturn(List.of());

        var response = controller.listPacksForAdmin(7L, "SYSTEM", "ACTIVE", "APPROVED", auth);

        assertEquals(List.of(), response.data());
        verify(access).ensurePlatformAdmin(user);
        verify(service).listPacksForAdmin(7L, "SYSTEM", "ACTIVE", "APPROVED");
    }
}
