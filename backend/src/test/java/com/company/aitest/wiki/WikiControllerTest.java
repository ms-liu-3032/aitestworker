package com.company.aitest.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.util.List;

import com.company.aitest.audit.OperationLogService;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class WikiControllerTest {

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
