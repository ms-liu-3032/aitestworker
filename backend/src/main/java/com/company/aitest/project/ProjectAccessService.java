package com.company.aitest.project;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ProjectAccessService {

    private final JdbcClient jdbc;

    public ProjectAccessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureCanAccess(Long projectId, CurrentUser user) {
        if (projectId == null) {
            throw new BusinessException("projectId 不能为空");
        }
        if (isPlatformAdmin(user)) {
            ensureProjectExists(projectId);
            return;
        }
        boolean exists = jdbc.sql("""
                SELECT COUNT(*) FROM project p
                JOIN project_member pm ON pm.project_id = p.id
                WHERE p.id = :projectId
                  AND p.status <> 'DELETED'
                  AND pm.user_id = :userId
                """)
                .param("projectId", projectId)
                .param("userId", user.id())
                .query(Integer.class)
                .single() > 0;
        if (!exists) {
            throw new BusinessException("无权访问当前项目");
        }
    }

    public void ensureCanManageProject(Long projectId, CurrentUser user) {
        if (projectId == null) {
            throw new BusinessException("projectId 不能为空");
        }
        if (isPlatformAdmin(user)) {
            ensureProjectExists(projectId);
            return;
        }
        boolean exists = jdbc.sql("""
                SELECT COUNT(*) FROM project p
                JOIN project_member pm ON pm.project_id = p.id
                WHERE p.id = :projectId
                  AND p.status <> 'DELETED'
                  AND pm.user_id = :userId
                  AND pm.project_role = 'OWNER'
                """)
                .param("projectId", projectId)
                .param("userId", user.id())
                .query(Integer.class)
                .single() > 0;
        if (!exists) {
            throw new BusinessException("无权管理当前项目");
        }
    }

    public void ensurePlatformAdmin(CurrentUser user) {
        if (!isPlatformAdmin(user)) {
            throw new BusinessException("只有管理员可以执行该操作");
        }
    }

    public boolean isPlatformAdmin(CurrentUser user) {
        return user != null && ("ADMIN".equals(user.roleCode()) || "SUB_ADMIN".equals(user.roleCode()));
    }

    private void ensureProjectExists(Long projectId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM project WHERE id = :projectId AND status <> 'DELETED'")
                .param("projectId", projectId)
                .query(Integer.class)
                .single() > 0;
        if (!exists) {
            throw new BusinessException("项目不存在或已删除");
        }
    }
}
