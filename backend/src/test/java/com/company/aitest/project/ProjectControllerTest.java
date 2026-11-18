package com.company.aitest.project;

import com.company.aitest.audit.OperationLogService;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ProjectControllerTest {
    @Test
    void projectDetailRequiresMembershipBeforeReadingMetadata() {
        ProjectService projects = mock(ProjectService.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        CurrentUser user = new CurrentUser(7L, "member", "USER");
        ProjectRecord project = new ProjectRecord(12L, "测试项目", "描述", "ACTIVE", 8L, null, null);
        when(projects.get(12L)).thenReturn(project);

        var result = new ProjectController(projects, access, mock(OperationLogService.class))
                .get(12L, new UsernamePasswordAuthenticationToken(user, null));

        assertEquals(12L, result.data().id());
        var order = inOrder(access, projects);
        order.verify(access).ensureCanAccess(12L, user);
        order.verify(projects).get(12L);
    }

    @Test
    void nonMemberCannotReadProjectMetadata() {
        ProjectService projects = mock(ProjectService.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        CurrentUser user = new CurrentUser(7L, "outsider", "USER");
        doThrow(new BusinessException("无权访问当前项目")).when(access).ensureCanAccess(12L, user);

        assertThrows(BusinessException.class, () ->
                new ProjectController(projects, access, mock(OperationLogService.class))
                        .get(12L, new UsernamePasswordAuthenticationToken(user, null)));
        verifyNoInteractions(projects);
    }
}
