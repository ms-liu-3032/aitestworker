package com.company.aitest.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class CaseExportControllerTest {

    private final CaseExportService exportService = mock(CaseExportService.class);
    private final ProjectAccessService accessService = mock(ProjectAccessService.class);
    private final CaseExportController controller = new CaseExportController(exportService, accessService);
    private final CurrentUser user = new CurrentUser(7L, "tester", "USER");
    private final Authentication authentication =
            new UsernamePasswordAuthenticationToken(user, null, List.of());

    @Test
    void localExportChecksProjectAccessAndPassesAuthenticatedUser() {
        byte[] expected = {1, 2, 3};
        when(exportService.exportLocalCasesToXmind(9L, List.of(8L, -11L), user)).thenReturn(expected);

        var response = controller.exportLocalCases(9L, List.of(8L, -11L), authentication);

        assertArrayEquals(expected, response.getBody());
        assertEquals("attachment; filename=\"local-cases.xmind\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        InOrder order = inOrder(accessService, exportService);
        order.verify(accessService).ensureCanAccess(9L, user);
        order.verify(exportService).exportLocalCasesToXmind(9L, List.of(8L, -11L), user);
    }

    @Test
    void formalExportChecksProjectAccessBeforeReadingCases() {
        byte[] expected = {4, 5, 6};
        when(exportService.exportFormalCasesToXmind(9L, List.of(21L))).thenReturn(expected);

        var response = controller.exportFormalCases(9L, List.of(21L), authentication);

        assertArrayEquals(expected, response.getBody());
        assertEquals("attachment; filename=\"formal-cases.xmind\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        InOrder order = inOrder(accessService, exportService);
        order.verify(accessService).ensureCanAccess(9L, user);
        order.verify(exportService).exportFormalCasesToXmind(9L, List.of(21L));
    }
}
