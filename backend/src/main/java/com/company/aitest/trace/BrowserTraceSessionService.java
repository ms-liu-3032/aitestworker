package com.company.aitest.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrowserTraceSessionService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public BrowserTraceSessionService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public BrowserTraceSessionRecord create(Long projectId, Long groupId, Long profileId, String sessionName,
                                             String browserType, String browserExecutablePath, CurrentUser user) {
        ensureGroupExists(projectId, groupId, user);
        ensureProfileExists(projectId, profileId, user);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into browser_trace_session(trace_group_id, project_id, user_id, profile_id,
                  session_name, browser_type, browser_executable_path, status, timezone, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?)
                """, groupId, projectId, user.id(), profileId, sessionName, browserType,
                browserExecutablePath, "Asia/Shanghai", now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getById(id, user);
    }

    public List<BrowserTraceSessionRecord> list(Long projectId, Long groupId, CurrentUser user) {
        ensureGroupExists(projectId, groupId, user);
        return jdbc.sql("""
                select * from browser_trace_session
                where trace_group_id = :groupId and project_id = :projectId and user_id = :userId
                order by id desc
                """).param("groupId", groupId).param("projectId", projectId).param("userId", user.id())
                .query(this::map).list();
    }

    public List<BrowserTraceSessionRecord> listByGroup(Long groupId, CurrentUser user) {
        return jdbc.sql("""
                select * from browser_trace_session
                where trace_group_id = :groupId and user_id = :userId
                order by id desc
                """).param("groupId", groupId).param("userId", user.id()).query(this::map).list();
    }

    @Transactional
    public BrowserTraceSessionRecord start(Long sessionId, CurrentUser user) {
        BrowserTraceSessionRecord session = getById(sessionId, user);
        LocalDateTime now = timeProvider.now();
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                update browser_trace_session
                set status = 'RECORDING', recording_started_at_utc = ?, recording_started_at_local = ?,
                    timezone = ?, updated_at = ?
                where id = ? and user_id = ?
                """, utcNow, now, "Asia/Shanghai", now, session.id(), user.id());
        return getById(sessionId, user);
    }

    @Transactional
    public BrowserTraceSessionRecord stop(Long sessionId, String videoPath, String traceFilePath,
                                          String screencastPath, LocalDateTime screencastStartedAtUtc,
                                          LocalDateTime screencastStoppedAtUtc, Long screencastDurationMs,
                                          CurrentUser user) {
        BrowserTraceSessionRecord session = getById(sessionId, user);
        LocalDateTime now = timeProvider.now();
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                update browser_trace_session
                set status = 'STOPPED', recording_stopped_at_utc = ?, recording_stopped_at_local = ?,
                    video_path = ?, trace_file_path = ?,
                    screencast_path = ?, screencast_started_at_utc = ?,
                    screencast_stopped_at_utc = ?, screencast_duration_ms = ?,
                    timezone = ?, updated_at = ?
                where id = ? and user_id = ?
                """, utcNow, now,
                valueOrCurrent(videoPath, session.videoPath()),
                valueOrCurrent(traceFilePath, session.traceFilePath()),
                valueOrCurrent(screencastPath, session.screencastPath()),
                screencastStartedAtUtc != null ? screencastStartedAtUtc : session.screencastStartedAtUtc(),
                screencastStoppedAtUtc != null ? screencastStoppedAtUtc : session.screencastStoppedAtUtc(),
                screencastDurationMs != null ? screencastDurationMs : session.screencastDurationMs(),
                "Asia/Shanghai", now, sessionId, user.id());
        return getById(sessionId, user);
    }

    /** 兼容旧调用方（无 screencast 字段） */
    @Transactional
    public BrowserTraceSessionRecord stop(Long sessionId, String videoPath, String traceFilePath, CurrentUser user) {
        return stop(sessionId, videoPath, traceFilePath, null, null, null, null, user);
    }

    @Transactional
    public BrowserTraceSessionRecord updateName(Long sessionId, String sessionName, CurrentUser user) {
        BrowserTraceSessionRecord session = getById(sessionId, user);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                update browser_trace_session
                set session_name = ?, updated_at = ?
                where id = ? and user_id = ?
                """, sessionName, now, sessionId, user.id());
        return getById(sessionId, user);
    }

    public BrowserTraceSessionRecord getById(Long id, CurrentUser user) {
        return jdbc.sql("select * from browser_trace_session where id = :id and user_id = :userId")
                .param("id", id).param("userId", user.id()).query(this::map).optional()
                .orElseThrow(() -> new BusinessException("采集会话不存在"));
    }

    private void ensureGroupExists(Long projectId, Long groupId, CurrentUser user) {
        boolean exists = jdbc.sql("""
                select count(*) from browser_trace_group
                where id = :id and project_id = :projectId and user_id = :userId
                """)
                .param("id", groupId).param("projectId", projectId).param("userId", user.id())
                .query(Integer.class).single() > 0;
        if (!exists) {
            throw new BusinessException("采集组不存在");
        }
    }

    private void ensureProfileExists(Long projectId, Long profileId, CurrentUser user) {
        boolean exists = jdbc.sql("""
                select count(*) from browser_profile
                where id = :id and project_id = :projectId and user_id = :userId
                """)
                .param("id", profileId).param("projectId", projectId).param("userId", user.id())
                .query(Integer.class).single() > 0;
        if (!exists) {
            throw new BusinessException("身份空间不存在或不属于当前项目");
        }
    }

    private String valueOrCurrent(String value, String current) {
        return value == null || value.isBlank() ? current : value;
    }

    private BrowserTraceSessionRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new BrowserTraceSessionRecord(
                rs.getLong("id"),
                rs.getLong("trace_group_id"),
                rs.getLong("project_id"),
                rs.getLong("user_id"),
                rs.getLong("profile_id"),
                rs.getString("session_name"),
                rs.getString("browser_type"),
                rs.getString("browser_executable_path"),
                rs.getString("video_path"),
                rs.getString("trace_file_path"),
                rs.getString("status"),
                rs.getTimestamp("recording_started_at_utc") != null
                        ? rs.getTimestamp("recording_started_at_utc").toLocalDateTime() : null,
                rs.getTimestamp("recording_started_at_local") != null
                        ? rs.getTimestamp("recording_started_at_local").toLocalDateTime() : null,
                rs.getTimestamp("recording_stopped_at_utc") != null
                        ? rs.getTimestamp("recording_stopped_at_utc").toLocalDateTime() : null,
                rs.getTimestamp("recording_stopped_at_local") != null
                        ? rs.getTimestamp("recording_stopped_at_local").toLocalDateTime() : null,
                rs.getString("timezone"),
                rs.getString("screencast_path"),
                rs.getTimestamp("screencast_started_at_utc") != null
                        ? rs.getTimestamp("screencast_started_at_utc").toLocalDateTime() : null,
                rs.getTimestamp("screencast_stopped_at_utc") != null
                        ? rs.getTimestamp("screencast_stopped_at_utc").toLocalDateTime() : null,
                getNullableLong(rs, "screencast_duration_ms"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private static Long getNullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
