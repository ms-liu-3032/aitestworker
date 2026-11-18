package com.company.aitest.project;

import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectMemberService {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ProjectAccessService access;
    private final TimeProvider clock;

    public ProjectMemberService(JdbcClient jdbc, JdbcTemplate template, ProjectAccessService access, TimeProvider clock) {
        this.jdbc = jdbc;
        this.template = template;
        this.access = access;
        this.clock = clock;
    }

    public record Member(Long userId, String username, String displayName, String projectRole) {}
    public record MemberList(boolean canManage, List<Member> members) {}

    public MemberList list(Long projectId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        boolean manager = access.isPlatformAdmin(user) || jdbc.sql("""
                SELECT COUNT(*) FROM project_member WHERE project_id = :projectId
                  AND user_id = :userId AND project_role = 'OWNER'
                """).param("projectId", projectId).param("userId", user.id())
                .query(Integer.class).single() > 0;
        return new MemberList(manager, jdbc.sql("""
                SELECT u.id, u.username, u.display_name, pm.project_role
                FROM project_member pm JOIN sys_user u ON u.id = pm.user_id
                WHERE pm.project_id = :projectId ORDER BY pm.id
                """).param("projectId", projectId).query((rs, row) -> new Member(
                rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("project_role"))).list());
    }

    @Transactional
    public Member add(Long projectId, String username, CurrentUser user) {
        access.ensureCanManageProject(projectId, user);
        if (username == null || username.isBlank() || username.length() > 64)
            throw new BusinessException("请输入有效的用户名");
        String normalized = username.trim();
        Long targetId = jdbc.sql("SELECT id FROM sys_user WHERE username = :username AND status = 'ACTIVE'")
                .param("username", normalized).query(Long.class).optional()
                .orElseThrow(() -> new BusinessException("用户不存在或已停用"));
        Integer count = jdbc.sql("SELECT COUNT(*) FROM project_member WHERE project_id = :projectId AND user_id = :userId")
                .param("projectId", projectId).param("userId", targetId).query(Integer.class).single();
        if (count > 0) throw new BusinessException("该用户已是项目成员");
        LocalDateTime now = clock.now();
        template.update("INSERT INTO project_member(project_id, user_id, project_role, created_at) VALUES (?, ?, 'MEMBER', ?)",
                projectId, targetId, now);
        return list(projectId, user).members().stream().filter(member -> targetId.equals(member.userId()))
                .findFirst().orElseThrow();
    }

    @Transactional
    public void remove(Long projectId, Long targetId, CurrentUser user) {
        access.ensureCanManageProject(projectId, user);
        String role = jdbc.sql("SELECT project_role FROM project_member WHERE project_id = :projectId AND user_id = :userId")
                .param("projectId", projectId).param("userId", targetId).query(String.class).optional()
                .orElseThrow(() -> new BusinessException("项目成员不存在"));
        if ("OWNER".equals(role)) throw new BusinessException("项目创建者不能被移除");
        template.update("DELETE FROM project_member WHERE project_id = ? AND user_id = ?", projectId, targetId);
    }
}
