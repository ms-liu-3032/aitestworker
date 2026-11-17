package com.company.aitest.dsh;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.ToIntFunction;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.company.aitest.trace.BrowserProfileService;
import com.company.aitest.trace.BrowserTraceGroupService;
import com.company.aitest.trace.BrowserTraceSessionService;
import com.company.aitest.trace.TraceDataService;
import com.company.aitest.trace.TraceRuntimeController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DshCaptureService {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TimeProvider clock;
    private final ProjectAccessService access;
    private final BrowserProfileService profiles;
    private final BrowserTraceGroupService groups;
    private final BrowserTraceSessionService sessions;
    private final TraceDataService traces;
    private final DshEvidenceStorageService files;
    private final DshWorkSessionService workSessions;

    public DshCaptureService(JdbcClient jdbc, JdbcTemplate template, TimeProvider clock, ProjectAccessService access,
                             BrowserProfileService profiles, BrowserTraceGroupService groups,
                             BrowserTraceSessionService sessions, TraceDataService traces,
                             DshEvidenceStorageService files, DshWorkSessionService workSessions) {
        this.jdbc = jdbc;
        this.template = template;
        this.clock = clock;
        this.access = access;
        this.profiles = profiles;
        this.groups = groups;
        this.sessions = sessions;
        this.traces = traces;
        this.files = files;
        this.workSessions = workSessions;
    }

    @Transactional
    public CaptureStart start(Long projectId, StartRequest request, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (request == null || request.targetUrl() == null || !request.targetUrl().matches("^https?://[^\\s]+$")) {
            throw new BusinessException("采集目标必须是 HTTP 或 HTTPS URL");
        }
        String name = request.name() == null || request.name().isBlank() ? "DSH 浏览器采集" : request.name().trim();
        if (name.length() > 128 || request.targetUrl().length() > 1024) throw new BusinessException("采集名称或目标地址过长");
        if (request.workSessionId() != null) workSessions.get(projectId, request.workSessionId(), user);
        var profile = profiles.create(projectId, name, request.targetUrl(),
                request.accountLabel(), request.roleLabel(), null, null, user);
        var group = groups.create(projectId, name, "DSH 本机采集", profile.id(), user);
        groups.start(group.id(), user);
        var session = sessions.create(projectId, group.id(), profile.id(), name, "CHROMIUM", null, user);
        sessions.start(session.id(), user);
        template.update("""
                INSERT INTO dsh_capture_run(session_id, project_id, user_id, group_id, profile_id,
                    work_session_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RECORDING', ?)
                """, session.id(), projectId, user.id(), group.id(), profile.id(), request.workSessionId(), clock.now());
        return new CaptureStart(session.id(), group.id(), profile.id(), request.workSessionId(), request.targetUrl());
    }

    public CaptureRun run(Long projectId, Long sessionId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        return jdbc.sql("""
                SELECT session_id, group_id, profile_id, work_session_id, status FROM dsh_capture_run
                WHERE session_id = :sessionId AND project_id = :projectId AND user_id = :userId
                """).param("sessionId", sessionId).param("projectId", projectId).param("userId", user.id())
                .query((rs, rowNum) -> new CaptureRun(rs.getLong("session_id"), rs.getLong("group_id"),
                        rs.getLong("profile_id"), rs.getObject("work_session_id", Long.class), rs.getString("status")))
                .optional().orElseThrow(() -> new BusinessException("采集会话不存在或无权访问"));
    }

    @Transactional
    public Evidence upload(Long projectId, Long sessionId, String kind, MultipartFile file, CurrentUser user) {
        run(projectId, sessionId, user);
        String status = jdbc.sql("SELECT status FROM dsh_capture_run WHERE session_id = :id FOR UPDATE")
                .param("id", sessionId).query(String.class).single();
        if (!"RECORDING".equals(status)) throw new BusinessException("采集会话已结束");
        var stored = files.store(projectId, sessionId, kind, file);
        template.update("""
                INSERT INTO dsh_evidence_file(session_id, project_id, user_id, kind, file_name, storage_path,
                    size_bytes, sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sessionId, projectId, user.id(), kind, stored.originalName(), stored.storagePath(),
                stored.sizeBytes(), stored.sha256(), clock.now());
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return new Evidence(id, kind, stored.originalName(), stored.sizeBytes(), stored.sha256());
    }

    public List<Evidence> evidence(Long projectId, Long sessionId, CurrentUser user) {
        run(projectId, sessionId, user);
        return jdbc.sql("""
                SELECT id, kind, file_name, size_bytes, sha256 FROM dsh_evidence_file
                WHERE session_id = :sessionId AND project_id = :projectId AND user_id = :userId ORDER BY id
                """).param("sessionId", sessionId).param("projectId", projectId).param("userId", user.id())
                .query((rs, rowNum) -> new Evidence(rs.getLong("id"), rs.getString("kind"),
                        rs.getString("file_name"), rs.getLong("size_bytes"), rs.getString("sha256"))).list();
    }

    public StoredEvidence stored(Long projectId, Long sessionId, Long evidenceId, CurrentUser user) {
        run(projectId, sessionId, user);
        return jdbc.sql("""
                SELECT kind, file_name, storage_path FROM dsh_evidence_file
                WHERE id = :id AND session_id = :sessionId AND project_id = :projectId AND user_id = :userId
                """).param("id", evidenceId).param("sessionId", sessionId)
                .param("projectId", projectId).param("userId", user.id())
                .query((rs, rowNum) -> new StoredEvidence(rs.getString("kind"), rs.getString("file_name"),
                        files.resolveStored(rs.getString("storage_path"))))
                .optional().orElseThrow(() -> new BusinessException("证据文件不存在或无权访问"));
    }

    @Transactional
    public CaptureFinished finish(Long projectId, Long sessionId, FinishRequest request, CurrentUser user) {
        CaptureRun run = run(projectId, sessionId, user);
        String status = jdbc.sql("SELECT status FROM dsh_capture_run WHERE session_id = :id FOR UPDATE")
                .param("id", sessionId).query(String.class).single();
        if ("FINISHED".equals(status)) return new CaptureFinished(sessionId, 0, 0, true);
        if (!"RECORDING".equals(status) || request == null) throw new BusinessException("采集会话状态无效");
        List<CaptureEvent> events = request.events() == null ? List.of() : request.events();
        List<TraceRuntimeController.NetworkItemRequest> networks = request.networks() == null ? List.of() : request.networks();
        if (events.size() > 20_000 || networks.size() > 20_000) throw new BusinessException("单次采集事件超过上限");
        List<TraceDataService.EventInput> eventInputs = events.stream().map(item -> {
            if (item == null || item.event() == null) throw new BusinessException("采集事件不能为空");
            var event = item.event();
            String screenshot = pathFor(projectId, sessionId, item.screenshotArtifactId(), "SCREENSHOT", user);
            return new TraceDataService.EventInput(event.eventType(), event.pageUrl(), event.pageTitle(),
                    event.elementText(), event.elementRole(), event.selector(), event.valueSummary(), screenshot,
                    event.normalizedLocator(), event.sectionTitle(), event.dialogTitle(), event.objectLabel(),
                    event.happenedAtUtc(), event.happenedAtLocal(), event.timezone(), event.relativeMs());
        }).toList();
        int eventCount = saveInBatches(eventInputs, batch -> traces.saveEvents(sessionId, batch, user));
        int networkCount = saveInBatches(networks.stream().map(item -> {
            if (item == null) throw new BusinessException("采集网络记录不能为空");
            return item.toInput();
        }).toList(), batch -> traces.saveNetworks(sessionId, batch, user));
        String video = pathForAny(projectId, sessionId, request.videoArtifactId(), SetOf.VIDEO_OR_SCREENSHOT, user);
        String trace = pathFor(projectId, sessionId, request.traceArtifactId(), "TRACE", user);
        String manifest = pathFor(projectId, sessionId, request.manifestArtifactId(), "MANIFEST", user);
        sessions.stop(sessionId, video, trace, manifest, request.screencastStartedAtUtc(),
                request.screencastStoppedAtUtc(), request.screencastDurationMs(), user);
        groups.stop(run.groupId(), user);
        template.update("UPDATE dsh_capture_run SET status = 'FINISHED', finished_at = ? WHERE session_id = ?",
                clock.now(), sessionId);
        return new CaptureFinished(sessionId, eventCount, networkCount, false);
    }

    private String pathFor(Long projectId, Long sessionId, Long id, String kind, CurrentUser user) {
        return pathForAny(projectId, sessionId, id, java.util.Set.of(kind), user);
    }

    private String pathForAny(Long projectId, Long sessionId, Long id, java.util.Set<String> kinds, CurrentUser user) {
        if (id == null) return null;
        StoredEvidence stored = stored(projectId, sessionId, id, user);
        if (!kinds.contains(stored.kind())) throw new BusinessException("证据类型与引用不匹配");
        return stored.path().toString();
    }

    private static final class SetOf {
        static final java.util.Set<String> VIDEO_OR_SCREENSHOT = java.util.Set.of("VIDEO", "SCREENSHOT");
    }

    static <T> int saveInBatches(List<T> items, ToIntFunction<List<T>> save) {
        int count = 0;
        for (int start = 0; start < items.size(); start += 500) {
            count += save.applyAsInt(items.subList(start, Math.min(start + 500, items.size())));
        }
        return count;
    }

    public record StartRequest(String targetUrl, String name, String accountLabel, String roleLabel, Long workSessionId) {}
    public record CaptureStart(Long sessionId, Long groupId, Long profileId, Long workSessionId, String targetUrl) {}
    public record CaptureRun(Long sessionId, Long groupId, Long profileId, Long workSessionId, String status) {}
    public record Evidence(Long id, String kind, String fileName, long sizeBytes, String sha256) {}
    public record StoredEvidence(String kind, String fileName, Path path) {}
    public record CaptureEvent(TraceRuntimeController.EventItemRequest event, Long screenshotArtifactId) {}
    public record FinishRequest(List<CaptureEvent> events, List<TraceRuntimeController.NetworkItemRequest> networks,
                                Long videoArtifactId, Long traceArtifactId, Long manifestArtifactId,
                                LocalDateTime screencastStartedAtUtc, LocalDateTime screencastStoppedAtUtc,
                                Long screencastDurationMs) {}
    public record CaptureFinished(Long sessionId, int eventCount, int networkCount, boolean alreadyFinished) {}
}
