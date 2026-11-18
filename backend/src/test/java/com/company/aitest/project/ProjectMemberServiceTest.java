package com.company.aitest.project;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ProjectMemberServiceTest {
    @Test
    void ordinaryMemberCannotAddOrRemoveOtherMembers() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcTemplate template = mock(JdbcTemplate.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        CurrentUser member = new CurrentUser(12L, "member", "USER");
        doThrow(new BusinessException("无权管理当前项目"))
                .when(access).ensureCanManageProject(3L, member);
        ProjectMemberService service = new ProjectMemberService(jdbc, template, access, mock(TimeProvider.class));

        assertThrows(BusinessException.class, () -> service.add(3L, "other", member));
        assertThrows(BusinessException.class, () -> service.remove(3L, 14L, member));
        verifyNoInteractions(jdbc, template);
    }
}
