package com.company.aitest.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public ProjectService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public List<ProjectRecord> list(CurrentUser user) {
        if ("ADMIN".equals(user.roleCode()) || "SUB_ADMIN".equals(user.roleCode())) {
            return jdbc.sql("select * from project where status <> 'DELETED' order by id desc").query(this::mapProject).list();
        }
        return jdbc.sql("""
                select p.* from project p
                join project_member pm on pm.project_id = p.id
                where pm.user_id = :userId
                  and p.status <> 'DELETED'
                order by p.id desc
                """).param("userId", user.id()).query(this::mapProject).list();
    }

    public PageResult<ProjectRecord> listPage(CurrentUser user, int page, int size, String keyword, String status) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.max(size, 1);
        int offset = (normalizedPage - 1) * normalizedSize;

        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (!"ADMIN".equals(user.roleCode()) && !"SUB_ADMIN".equals(user.roleCode())) {
            where.append(" and exists (select 1 from project_member pm where pm.project_id = p.id and pm.user_id = :userId)");
            params.put("userId", user.id());
            where.append(" and p.status <> 'DELETED'");
        } else if (status == null || status.isBlank() || "ACTIVE".equalsIgnoreCase(status)) {
            where.append(" and p.status <> 'DELETED'");
        } else if ("DELETED".equalsIgnoreCase(status)) {
            where.append(" and p.status = 'DELETED'");
        } else if (!"ALL".equalsIgnoreCase(status)) {
            where.append(" and p.status = :status");
            params.put("status", status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (p.project_name like :keyword or p.description like :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }

        int total = jdbc.sql("select count(*) from project p" + where)
                .params(params)
                .query(Integer.class)
                .single();
        List<ProjectRecord> items = jdbc.sql("select p.* from project p" + where + " order by p.id desc limit :limit offset :offset")
                .params(params)
                .param("limit", normalizedSize)
                .param("offset", offset)
                .query(this::mapProject)
                .list();
        return new PageResult<>(items, total, normalizedPage, normalizedSize);
    }

    @Transactional
    public ProjectRecord create(String projectName, String description, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into project(project_name, description, status, created_by, created_at, updated_at)
                values (?, ?, 'ACTIVE', ?, ?, ?)
                """, projectName, description, user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        jdbcTemplate.update("insert into project_member(project_id, user_id, project_role, created_at) values (?, ?, 'OWNER', ?)",
                id, user.id(), now);
        return get(id);
    }

    @Transactional
    public ProjectRecord update(Long id, String projectName, String description, CurrentUser user) {
        ensureAdmin(user);
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                update project
                set project_name = ?, description = ?, updated_at = ?
                where id = ? and status <> 'DELETED'
                """, projectName, description, now, id);
        if (affected == 0) {
            throw new BusinessException("项目不存在或已删除");
        }
        return get(id);
    }

    @Transactional
    public void delete(Long id, CurrentUser user) {
        ensureAdmin(user);
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                update project
                set status = 'DELETED', updated_at = ?
                where id = ? and status <> 'DELETED'
                """, now, id);
        if (affected == 0) {
            throw new BusinessException("项目不存在或已删除");
        }
    }

    @Transactional
    public ProjectRecord restore(Long id, CurrentUser user) {
        ensureAdmin(user);
        LocalDateTime now = timeProvider.now();
        int affected = jdbcTemplate.update("""
                update project
                set status = 'ACTIVE', updated_at = ?
                where id = ? and status = 'DELETED'
                """, now, id);
        if (affected == 0) {
            throw new BusinessException("项目不存在或未处于已删除状态");
        }
        return get(id);
    }

    public ProjectRecord get(Long id) {
        List<ProjectRecord> items = jdbc.sql("select * from project where id = :id and status <> 'DELETED'")
                .param("id", id)
                .query(this::mapProject)
                .list();
        if (items.isEmpty()) {
            throw new BusinessException("项目不存在或已删除");
        }
        return items.get(0);
    }

    private void ensureAdmin(CurrentUser user) {
        if (!"ADMIN".equals(user.roleCode()) && !"SUB_ADMIN".equals(user.roleCode())) {
            throw new BusinessException("只有管理员可以编辑或删除项目");
        }
    }

    private ProjectRecord mapProject(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectRecord(
                rs.getLong("id"),
                rs.getString("project_name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
