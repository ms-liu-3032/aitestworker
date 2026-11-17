package com.company.aitest.dsh;

import com.company.aitest.audit.OperationLogService;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectRecord;
import com.company.aitest.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DshProjectControllerTest {
    @Test
    void createsThroughExistingProjectServiceWithBoundUser() {
        ProjectService projects = mock(ProjectService.class);
        OperationLogService logs = mock(OperationLogService.class);
        CurrentUser user = new CurrentUser(9L, "tester", "USER");
        ProjectRecord project = new ProjectRecord(16L, "访客模块", "新版回归测试", "ACTIVE", 9L, null, null);
        when(projects.create("访客模块", "新版回归测试", user)).thenReturn(project);
        var auth = new UsernamePasswordAuthenticationToken(new DshConnectionService.DshPrincipal(3L, user), null);

        var result = new DshProjectController(projects, logs)
                .create(new DshProjectController.CreateRequest(" 访客模块 ", "新版回归测试"), auth);

        assertEquals(16L, result.data().id());
        verify(projects).create("访客模块", "新版回归测试", user);
        verify(logs).recordQuietly(eq(9L), eq("PROJECT_CREATE"), eq("PROJECT"), eq(16L), anyString());
    }
}
