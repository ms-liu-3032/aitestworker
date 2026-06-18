package com.company.aitest.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
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
public class BrowserTraceGroupService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public BrowserTraceGroupService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public BrowserTraceGroupRecord create(Long projectId, String groupName, String description,
                                          Long profileId, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into browser_trace_group(project_id, user_id, profile_id, group_name, description, status,
                  timezone, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, projectId, user.id(), profileId, groupName, description, "Asia/Shanghai", now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getById(projectId, id, user);
    }

    @Transactional
    public void delete(Long projectId, Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = getById(projectId, groupId, user);
        if ("RECORDING".equals(group.status())) {
            throw new BusinessException("采集组正在录制中，请先停止后再删除");
        }
        jdbcTemplate.update("DELETE FROM browser_issue_clip WHERE trace_group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM browser_trace_network WHERE trace_group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM browser_trace_event WHERE trace_group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM browser_trace_session WHERE trace_group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM browser_trace_group WHERE id = ? AND user_id = ?", groupId, user.id());
    }

    public List<BrowserTraceGroupRecord> list(Long projectId, CurrentUser user) {
        return jdbc.sql("""
                select * from browser_trace_group
                where project_id = :projectId and user_id = :userId
                order by id desc
                """).param("projectId", projectId).param("userId", user.id()).query(this::map).list();
    }

    public BrowserTraceGroupRecord get(Long projectId, Long groupId, CurrentUser user) {
        return getById(projectId, groupId, user);
    }

    @Transactional
    public BrowserTraceGroupRecord start(Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = getById(groupId, user);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update browser_trace_group
                set status = 'RECORDING', started_at = ?, updated_at = ?, timezone = ?
                where id = ? and user_id = ?
                """, now, now, "Asia/Shanghai", groupId, user.id());
        return getById(group.projectId(), groupId, user);
    }

    @Transactional
    public BrowserTraceGroupRecord stop(Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = getById(groupId, user);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update browser_trace_group
                set status = 'STOPPED', stopped_at = ?, updated_at = ?, timezone = ?
                where id = ? and user_id = ?
                """, now, now, "Asia/Shanghai", groupId, user.id());
        return getById(group.projectId(), groupId, user);
    }

    public BrowserTraceGroupRecord getById(Long groupId, CurrentUser user) {
        return jdbc.sql("select * from browser_trace_group where id = :id and user_id = :userId")
                .param("id", groupId).param("userId", user.id()).query(this::map).optional()
                .orElseThrow(() -> new BusinessException("采集组不存在"));
    }

    private BrowserTraceGroupRecord getById(Long projectId, Long groupId, CurrentUser user) {
        return jdbc.sql("""
                select * from browser_trace_group
                where id = :id and project_id = :projectId and user_id = :userId
                """)
                .param("id", groupId).param("projectId", projectId).param("userId", user.id())
                .query(this::map).optional()
                .orElseThrow(() -> new BusinessException("采集组不存在"));
    }

    private BrowserTraceGroupRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new BrowserTraceGroupRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getLong("user_id"),
                getLongNullable(rs, "profile_id"),
                rs.getString("group_name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toLocalDateTime() : null,
                rs.getTimestamp("stopped_at") != null ? rs.getTimestamp("stopped_at").toLocalDateTime() : null,
                rs.getString("timezone"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private Long getLongNullable(ResultSet rs, String column) throws SQLException {
        long val = rs.getLong(column);
        return rs.wasNull() ? null : val;
    }
}
