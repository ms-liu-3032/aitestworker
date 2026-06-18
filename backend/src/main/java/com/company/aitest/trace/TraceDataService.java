package com.company.aitest.trace;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceDataService {
    private static final int MAX_BATCH_SIZE = 500;
    private static final int SUMMARY_LIMIT = 8192;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final BrowserTraceGroupService groupService;
    private final BrowserTraceSessionService sessionService;

    public TraceDataService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                            BrowserTraceGroupService groupService, BrowserTraceSessionService sessionService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.groupService = groupService;
        this.sessionService = sessionService;
    }

    @Transactional
    public int saveEvents(Long sessionId, List<EventInput> events, CurrentUser user) {
        ensureBatchSize(events == null ? 0 : events.size());
        BrowserTraceSessionRecord session = sessionService.getById(sessionId, user);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.batchUpdate("""
                insert into browser_trace_event(trace_group_id, trace_session_id, profile_id, event_type,
                  page_url, page_title, element_text, element_role, selector, value_summary, screenshot_path,
                  normalized_locator, section_title, dialog_title, object_label,
                  happened_at_utc, happened_at_local, timezone, relative_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                EventInput item = events.get(i);
                ps.setLong(1, session.traceGroupId());
                ps.setLong(2, session.id());
                ps.setLong(3, session.profileId());
                ps.setString(4, required(item.eventType(), "事件类型不能为空"));
                ps.setString(5, item.pageUrl());
                ps.setString(6, item.pageTitle());
                ps.setString(7, item.elementText());
                ps.setString(8, item.elementRole());
                ps.setString(9, item.selector());
                ps.setString(10, item.valueSummary());
                ps.setString(11, item.screenshotPath());
                ps.setString(12, blankToNull(item.normalizedLocator()));
                ps.setString(13, blankToNull(item.sectionTitle()));
                ps.setString(14, blankToNull(item.dialogTitle()));
                ps.setString(15, blankToNull(item.objectLabel()));
                ps.setTimestamp(16, ts(item.happenedAtUtc() == null ? now : item.happenedAtUtc()));
                ps.setTimestamp(17, ts(item.happenedAtLocal() == null ? now : item.happenedAtLocal()));
                ps.setString(18, item.timezone() == null ? "Asia/Shanghai" : item.timezone());
                ps.setLong(19, item.relativeMs() == null ? 0L : item.relativeMs());
                ps.setTimestamp(20, ts(now));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
        return events.size();
    }

    @Transactional
    public int saveNetworks(Long sessionId, List<NetworkInput> items, CurrentUser user) {
        ensureBatchSize(items == null ? 0 : items.size());
        BrowserTraceSessionRecord session = sessionService.getById(sessionId, user);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.batchUpdate("""
                insert into browser_trace_network(trace_group_id, trace_session_id, profile_id, url, method,
                  status_code, duration_ms, failed, error_message, request_summary, response_summary,
                  request_started_at_utc, request_started_at_local, response_ended_at_utc,
                  response_ended_at_local, timezone, relative_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                NetworkInput item = items.get(i);
                ps.setLong(1, session.traceGroupId());
                ps.setLong(2, session.id());
                ps.setLong(3, session.profileId());
                ps.setString(4, required(item.url(), "请求 URL 不能为空"));
                ps.setString(5, item.method());
                setInteger(ps, 6, item.statusCode());
                setLong(ps, 7, item.durationMs());
                ps.setInt(8, Boolean.TRUE.equals(item.failed()) ? 1 : 0);
                ps.setString(9, item.errorMessage());
                ps.setString(10, truncate(item.requestSummary()));
                ps.setString(11, truncate(item.responseSummary()));
                ps.setTimestamp(12, tsNullable(item.requestStartedAtUtc()));
                ps.setTimestamp(13, tsNullable(item.requestStartedAtLocal()));
                ps.setTimestamp(14, tsNullable(item.responseEndedAtUtc()));
                ps.setTimestamp(15, tsNullable(item.responseEndedAtLocal()));
                ps.setString(16, item.timezone() == null ? "Asia/Shanghai" : item.timezone());
                setLong(ps, 17, item.relativeMs());
                ps.setTimestamp(18, ts(now));
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        });
        return items.size();
    }

    @Transactional
    public BrowserIssueClipRecord createIssueClip(Long groupId, IssueClipInput input, CurrentUser user) {
        groupService.getById(groupId, user);
        ensureClipScope(input.clipScope());
        ensureIssueClipRef(groupId, input, user);
        LocalDateTime now = timeProvider.now();

        // Sprint 4 · M4.3：若 issue clip 关联到某个 session 且该 session 有 screencast，
        // 自动继承 screencast_path 并把 clipStartRelativeMs / clipEndRelativeMs 映射成
        // 视频上的播放区间（screencast_clip_*_ms）
        String screencastPath = null;
        Long screencastClipStartMs = null;
        Long screencastClipEndMs = null;
        if (input.traceSessionId() != null) {
            try {
                var session = sessionService.getById(input.traceSessionId(), user);
                if (session.screencastPath() != null && !session.screencastPath().isBlank()) {
                    screencastPath = session.screencastPath();
                    screencastClipStartMs = input.clipStartRelativeMs();
                    screencastClipEndMs = input.clipEndRelativeMs();
                }
            } catch (RuntimeException ignored) {
                // session 不存在 / 无权限：不阻断 issue clip 创建
            }
        }

        jdbcTemplate.update("""
                insert into browser_issue_clip(trace_group_id, trace_session_id, profile_id, clip_scope,
                  title, description, clip_start_at_utc, clip_start_at_local, clip_end_at_utc,
                  clip_end_at_local, clip_start_relative_ms, clip_end_relative_ms, timezone, status,
                  created_by, screencast_path, screencast_clip_start_ms, screencast_clip_end_ms,
                  created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, groupId, input.traceSessionId(), input.profileId(), input.clipScope(), input.title(),
                input.description(), input.clipStartAtUtc(), input.clipStartAtLocal(), input.clipEndAtUtc(),
                input.clipEndAtLocal(), input.clipStartRelativeMs(), input.clipEndRelativeMs(),
                input.timezone() == null ? "Asia/Shanghai" : input.timezone(), user.id(),
                screencastPath, screencastClipStartMs, screencastClipEndMs,
                now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return jdbc.sql("select * from browser_issue_clip where id = :id")
                .param("id", id).query(this::mapIssueClip).single();
    }

    public List<BrowserIssueClipRecord> listIssueClips(Long groupId, CurrentUser user) {
        groupService.getById(groupId, user);
        return jdbc.sql("select * from browser_issue_clip where trace_group_id = :groupId order by id desc")
                .param("groupId", groupId).query(this::mapIssueClip).list();
    }

    public TraceGroupDetail detail(Long groupId, CurrentUser user) {
        BrowserTraceGroupRecord group = groupService.getById(groupId, user);
        List<BrowserTraceSessionRecord> sessions = sessionService.listByGroup(groupId, user);
        List<BrowserTraceEventRecord> events = jdbc.sql("""
                select * from browser_trace_event
                where trace_group_id = :groupId
                order by relative_ms asc, id asc
                limit 1000
                """).param("groupId", groupId).query(this::mapEvent).list();
        List<BrowserTraceNetworkRecord> networks = jdbc.sql("""
                select * from browser_trace_network
                where trace_group_id = :groupId
                order by relative_ms asc, id asc
                limit 1000
                """).param("groupId", groupId).query(this::mapNetwork).list();
        List<BrowserIssueClipRecord> issueClips = listIssueClips(groupId, user);
        return new TraceGroupDetail(group, sessions, events, networks, issueClips);
    }

    private void ensureBatchSize(int size) {
        if (size <= 0) {
            throw new BusinessException("批量数据不能为空");
        }
        if (size > MAX_BATCH_SIZE) {
            throw new BusinessException("单次最多接收 500 条数据");
        }
    }

    private void ensureClipScope(String clipScope) {
        try {
            IssueClipScope.valueOf(clipScope);
        } catch (RuntimeException ex) {
            throw new BusinessException("问题片段范围不支持");
        }
    }

    private void ensureIssueClipRef(Long groupId, IssueClipInput input, CurrentUser user) {
        if (input.clipStartAtUtc() == null || input.clipStartAtLocal() == null
                || input.clipEndAtUtc() == null || input.clipEndAtLocal() == null
                || input.clipStartRelativeMs() == null || input.clipEndRelativeMs() == null) {
            throw new BusinessException("问题片段时间范围不能为空");
        }
        if (input.traceSessionId() != null) {
            BrowserTraceSessionRecord session = sessionService.getById(input.traceSessionId(), user);
            if (!session.traceGroupId().equals(groupId)) {
                throw new BusinessException("问题片段会话不属于当前采集组");
            }
        }
        if (input.profileId() != null) {
            boolean exists = jdbc.sql("""
                    select count(*) from browser_profile
                    where id = :profileId and user_id = :userId
                    """).param("profileId", input.profileId()).param("userId", user.id())
                    .query(Integer.class).single() > 0;
            if (!exists) {
                throw new BusinessException("问题片段身份空间不存在");
            }
        }
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_LIMIT) {
            return value;
        }
        return value.substring(0, SUMMARY_LIMIT);
    }

    private Timestamp ts(LocalDateTime value) {
        return Timestamp.valueOf(value);
    }

    private Timestamp tsNullable(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setInt(index, value);
        }
    }

    private void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private BrowserIssueClipRecord mapIssueClip(ResultSet rs, int rowNum) throws SQLException {
        return new BrowserIssueClipRecord(
                rs.getLong("id"),
                rs.getLong("trace_group_id"),
                getLongNullable(rs, "trace_session_id"),
                getLongNullable(rs, "profile_id"),
                rs.getString("clip_scope"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getTimestamp("clip_start_at_utc").toLocalDateTime(),
                rs.getTimestamp("clip_start_at_local").toLocalDateTime(),
                rs.getTimestamp("clip_end_at_utc").toLocalDateTime(),
                rs.getTimestamp("clip_end_at_local").toLocalDateTime(),
                rs.getLong("clip_start_relative_ms"),
                rs.getLong("clip_end_relative_ms"),
                rs.getString("timezone"),
                rs.getString("status"),
                getLongNullable(rs, "created_by"),
                rs.getString("screencast_path"),
                getLongNullable(rs, "screencast_clip_start_ms"),
                getLongNullable(rs, "screencast_clip_end_ms"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private BrowserTraceEventRecord mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new BrowserTraceEventRecord(
                rs.getLong("id"),
                rs.getLong("trace_group_id"),
                rs.getLong("trace_session_id"),
                rs.getLong("profile_id"),
                rs.getString("event_type"),
                rs.getString("page_url"),
                rs.getString("page_title"),
                rs.getString("element_text"),
                rs.getString("element_role"),
                rs.getString("selector"),
                rs.getString("value_summary"),
                rs.getString("screenshot_path"),
                rs.getString("normalized_locator"),
                rs.getString("section_title"),
                rs.getString("dialog_title"),
                rs.getString("object_label"),
                rs.getTimestamp("happened_at_utc").toLocalDateTime(),
                rs.getTimestamp("happened_at_local").toLocalDateTime(),
                rs.getString("timezone"),
                rs.getLong("relative_ms"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private BrowserTraceNetworkRecord mapNetwork(ResultSet rs, int rowNum) throws SQLException {
        return new BrowserTraceNetworkRecord(
                rs.getLong("id"),
                rs.getLong("trace_group_id"),
                rs.getLong("trace_session_id"),
                rs.getLong("profile_id"),
                rs.getString("url"),
                rs.getString("method"),
                getIntegerNullable(rs, "status_code"),
                getLongNullable(rs, "duration_ms"),
                rs.getInt("failed"),
                rs.getString("error_message"),
                rs.getString("request_summary"),
                rs.getString("response_summary"),
                toLocalDateTime(rs, "request_started_at_utc"),
                toLocalDateTime(rs, "request_started_at_local"),
                toLocalDateTime(rs, "response_ended_at_utc"),
                toLocalDateTime(rs, "response_ended_at_local"),
                rs.getString("timezone"),
                getLongNullable(rs, "relative_ms"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private Integer getIntegerNullable(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long getLongNullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record EventInput(String eventType, String pageUrl, String pageTitle, String elementText,
                             String elementRole, String selector, String valueSummary, String screenshotPath,
                             String normalizedLocator, String sectionTitle, String dialogTitle, String objectLabel,
                             LocalDateTime happenedAtUtc, LocalDateTime happenedAtLocal, String timezone,
                             Long relativeMs) {
    }

    public record NetworkInput(String url, String method, Integer statusCode, Long durationMs, Boolean failed,
                               String errorMessage, String requestSummary, String responseSummary,
                               LocalDateTime requestStartedAtUtc, LocalDateTime requestStartedAtLocal,
                               LocalDateTime responseEndedAtUtc, LocalDateTime responseEndedAtLocal,
                               String timezone, Long relativeMs) {
    }

    public record IssueClipInput(Long traceSessionId, Long profileId, String clipScope, String title,
                                 String description, LocalDateTime clipStartAtUtc,
                                 LocalDateTime clipStartAtLocal, LocalDateTime clipEndAtUtc,
                                 LocalDateTime clipEndAtLocal, Long clipStartRelativeMs,
                                 Long clipEndRelativeMs, String timezone) {
    }

    public record TraceGroupDetail(BrowserTraceGroupRecord group, List<BrowserTraceSessionRecord> sessions,
                                   List<BrowserTraceEventRecord> events,
                                   List<BrowserTraceNetworkRecord> networks,
                                   List<BrowserIssueClipRecord> issueClips) {
    }
}
