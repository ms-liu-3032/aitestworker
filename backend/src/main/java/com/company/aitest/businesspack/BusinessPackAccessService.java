package com.company.aitest.businesspack;

import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class BusinessPackAccessService {
    private final ProjectAccessService projectAccess;
    private final JdbcClient jdbc;

    public BusinessPackAccessService(ProjectAccessService projectAccess, JdbcClient jdbc) {
        this.projectAccess = projectAccess;
        this.jdbc = jdbc;
    }

    public void authorize(Long projectId, Map<String, String> pathVariables, CurrentUser user, String method) {
        projectAccess.ensureCanAccess(projectId, user);
        if (!"GET".equals(method)) projectAccess.ensureCanManageProject(projectId, user);
        checkOwner(projectId, pathVariables.get("packId"), "business_pack", null);
        checkOwner(projectId, pathVariables.get("itemId"), "business_pack_item", pathVariables.get("packId"));
        checkOwner(projectId, pathVariables.get("relationId"), "business_pack_relation", null);
        checkOwner(projectId, pathVariables.get("snapshotId"), "business_pack_snapshot", null);
    }

    private void checkOwner(Long projectId, String rawId, String table, String rawPackId) {
        if (rawId == null) return;
        long id = parseId(rawId);
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE id = :id AND project_id = :projectId";
        if (rawPackId != null) sql += " AND pack_id = :packId";
        var query = jdbc.sql(sql).param("id", id).param("projectId", projectId);
        if (rawPackId != null) query = query.param("packId", parseId(rawPackId));
        if (query.query(Integer.class).single() == 0) throw new BusinessException("业务包资产不属于当前项目");
    }

    private long parseId(String rawId) {
        try { return Long.parseLong(rawId); }
        catch (NumberFormatException exception) { throw new BusinessException("业务包资产编号无效"); }
    }
}
