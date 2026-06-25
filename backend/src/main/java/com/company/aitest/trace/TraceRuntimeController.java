package com.company.aitest.trace;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.trace.TraceAutoScanService.ScanResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trace")
public class TraceRuntimeController {
    private final BrowserTraceGroupService groupService;
    private final BrowserTraceSessionService sessionService;
    private final TraceDataService traceDataService;
    private final TraceAutoScanService traceAutoScanService;

    public TraceRuntimeController(BrowserTraceGroupService groupService, BrowserTraceSessionService sessionService,
                                  TraceDataService traceDataService, TraceAutoScanService traceAutoScanService) {
        this.groupService = groupService;
        this.sessionService = sessionService;
        this.traceDataService = traceDataService;
        this.traceAutoScanService = traceAutoScanService;
    }

    @PostMapping("/groups/{groupId}/start")
    public ApiResponse<BrowserTraceGroupRecord> startGroup(@PathVariable Long groupId,
                                                            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(groupService.start(groupId, user));
    }

    @PostMapping("/groups/{groupId}/stop")
    public ApiResponse<BrowserTraceGroupRecord> stopGroup(@PathVariable Long groupId,
                                                           Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(groupService.stop(groupId, user));
    }

    @PostMapping("/groups/{groupId}/sessions")
    public ApiResponse<BrowserTraceSessionRecord> createSession(@PathVariable Long groupId,
                                                                 @Valid @RequestBody CreateSessionRequest request,
                                                                 Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        BrowserTraceGroupRecord group = groupService.getById(groupId, user);
        return ApiResponse.ok(sessionService.create(group.projectId(), groupId, request.profileId(),
                request.sessionName(), request.browserType(), request.browserExecutablePath(), user));
    }

    @GetMapping("/groups/{groupId}/sessions")
    public ApiResponse<List<BrowserTraceSessionRecord>> listSessions(@PathVariable Long groupId,
                                                                      Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        groupService.getById(groupId, user);
        return ApiResponse.ok(sessionService.listByGroup(groupId, user));
    }

    @PostMapping("/sessions/{sessionId}/start")
    public ApiResponse<BrowserTraceSessionRecord> startSession(@PathVariable Long sessionId,
                                                                Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(sessionService.start(sessionId, user));
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ApiResponse<BrowserTraceSessionRecord> stopSession(@PathVariable Long sessionId,
                                                               @RequestBody(required = false) StopSessionRequest request,
                                                               Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        StopSessionRequest body = request == null ? new StopSessionRequest(null, null) : request;
        BrowserTraceSessionRecord stopped = sessionService.stop(sessionId,
                body.videoPath(), body.traceFilePath(),
                body.screencastPath(), body.screencastStartedAtUtc(),
                body.screencastStoppedAtUtc(), body.screencastDurationMs(),
                user);
        traceAutoScanService.scheduleSessionAutoScan(stopped, user);
        return ApiResponse.ok(stopped);
    }

    @PostMapping("/sessions/{sessionId}/events:batch")
    public ApiResponse<BatchResult> saveEvents(@PathVariable Long sessionId,
                                                @Valid @RequestBody EventBatchRequest request,
                                                Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        List<EventItemRequest> events = request.events() == null ? List.of() : request.events();
        int count = traceDataService.saveEvents(sessionId, events.stream().map(EventItemRequest::toInput).toList(), user);
        return ApiResponse.ok(new BatchResult(count));
    }

    @PostMapping("/sessions/{sessionId}/network:batch")
    public ApiResponse<BatchResult> saveNetworks(@PathVariable Long sessionId,
                                                  @Valid @RequestBody NetworkBatchRequest request,
                                                  Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        List<NetworkItemRequest> items = request.items() == null ? List.of() : request.items();
        int count = traceDataService.saveNetworks(sessionId, items.stream().map(NetworkItemRequest::toInput).toList(), user);
        return ApiResponse.ok(new BatchResult(count));
    }

    @PostMapping("/groups/{groupId}/issue-clips")
    public ApiResponse<BrowserIssueClipRecord> createIssueClip(@PathVariable Long groupId,
                                                                @Valid @RequestBody IssueClipRequest request,
                                                                Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceDataService.createIssueClip(groupId, request.toInput(), user));
    }

    /**
     * Sprint 4 · M4.2 / M4.7 · 主录屏文件下载或流式播放。
     * <p>
     * 兼容两种 screencast 格式：
     * <ul>
     *   <li>**CDP 帧序列（M4.7 起的默认）**：path 指向 manifest.json，则按 application/json 返回</li>
     *   <li>**WebM（M4.2 早期实现）**：path 指向 .webm 文件，则按 video/webm 流式返回</li>
     * </ul>
     * 路径安全：从 DB 读取，不接受请求参数自定义路径。
     */
    @GetMapping("/sessions/{sessionId}/screencast")
    public ResponseEntity<Resource> downloadScreencast(@PathVariable Long sessionId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        BrowserTraceSessionRecord session = sessionService.getById(sessionId, user);
        String pathStr = session.screencastPath();
        if (pathStr == null || pathStr.isBlank()) {
            throw new BusinessException("该采集会话没有可下载的主录屏文件");
        }
        File file = new File(pathStr);
        if (!file.exists() || !file.isFile()) {
            throw new BusinessException("主录屏文件已丢失：" + pathStr);
        }
        Resource resource = new FileSystemResource(file);
        boolean isManifest = pathStr.toLowerCase().endsWith(".json");
        String fileName = "session-" + sessionId + (isManifest ? "-screencast-manifest.json" : "-screencast.webm");
        MediaType mt = isManifest ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("video/webm");
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .contentLength(file.length())
                .body(resource);
    }

    /**
     * Sprint 4 · M4.7 · CDP 帧序列：取 manifest.json（与 /screencast 当 path 是 .json 时等价，
     * 但语义化路径方便前端走两个端点）。
     */
    @GetMapping("/sessions/{sessionId}/screencast/manifest")
    public ResponseEntity<Resource> screencastManifest(@PathVariable Long sessionId, Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        BrowserTraceSessionRecord session = sessionService.getById(sessionId, user);
        String pathStr = session.screencastPath();
        if (pathStr == null || pathStr.isBlank() || !pathStr.toLowerCase().endsWith(".json")) {
            throw new BusinessException("该采集会话没有 CDP 帧序列 manifest");
        }
        File file = new File(pathStr);
        if (!file.exists() || !file.isFile()) {
            throw new BusinessException("manifest 文件已丢失：" + pathStr);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }

    /**
     * Sprint 4 · M4.7 · CDP 帧序列：按文件名取一帧 JPEG。
     * <p>
     * 路径安全：filename 必须匹配 `^\d{6}\.jpg$`，且实际查询的目录由 DB 中 manifest.json
     * 所在 `frames/` 子目录强制拼出，杜绝 path traversal。
     */
    @GetMapping("/sessions/{sessionId}/screencast/frame/{filename}")
    public ResponseEntity<Resource> screencastFrame(@PathVariable Long sessionId,
                                                    @PathVariable String filename,
                                                    Authentication authentication) {
        if (filename == null || !filename.matches("^\\d{6}\\.jpg$")) {
            throw new BusinessException("非法的帧文件名");
        }
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        BrowserTraceSessionRecord session = sessionService.getById(sessionId, user);
        String pathStr = session.screencastPath();
        if (pathStr == null || pathStr.isBlank() || !pathStr.toLowerCase().endsWith(".json")) {
            throw new BusinessException("该采集会话没有 CDP 帧序列");
        }
        File manifestFile = new File(pathStr);
        File framesDir = new File(manifestFile.getParentFile(), "frames");
        File frame = new File(framesDir, filename);
        if (!frame.exists() || !frame.isFile()) {
            throw new BusinessException("帧文件不存在：" + filename);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(frame.length())
                .body(new FileSystemResource(frame));
    }

    @GetMapping("/groups/{groupId}/issue-clips")
    public ApiResponse<List<BrowserIssueClipRecord>> listIssueClips(@PathVariable Long groupId,
                                                                     Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceDataService.listIssueClips(groupId, user));
    }

    @GetMapping("/groups/{groupId}/detail")
    public ApiResponse<TraceDataService.TraceGroupDetail> detail(@PathVariable Long groupId,
                                                                  Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(traceDataService.detail(groupId, user));
    }

    public record CreateSessionRequest(@NotNull Long profileId, String sessionName, @NotBlank String browserType,
                                       String browserExecutablePath) {
    }

    public record StopSessionRequest(String videoPath, String traceFilePath,
                                     String screencastPath, LocalDateTime screencastStartedAtUtc,
                                     LocalDateTime screencastStoppedAtUtc, Long screencastDurationMs) {
        public StopSessionRequest(String videoPath, String traceFilePath) {
            this(videoPath, traceFilePath, null, null, null, null);
        }
    }

    public record EventBatchRequest(List<EventItemRequest> events) {
    }

    public record EventItemRequest(@NotBlank String eventType, String pageUrl, String pageTitle,
                                   String elementText, String elementRole, String selector,
                                   String valueSummary, String screenshotPath,
                                   String normalizedLocator, String sectionTitle, String dialogTitle,
                                   String objectLabel, LocalDateTime happenedAtUtc,
                                   LocalDateTime happenedAtLocal, String timezone, Long relativeMs) {
        TraceDataService.EventInput toInput() {
            return new TraceDataService.EventInput(eventType, pageUrl, pageTitle, elementText, elementRole,
                    selector, valueSummary, screenshotPath, normalizedLocator, sectionTitle, dialogTitle,
                    objectLabel, happenedAtUtc, happenedAtLocal, timezone, relativeMs);
        }
    }

    public record NetworkBatchRequest(List<NetworkItemRequest> items) {
    }

    public record NetworkItemRequest(@NotBlank String url, String method, Integer statusCode, Long durationMs,
                                     Boolean failed, String errorMessage, String requestSummary,
                                     String responseSummary, LocalDateTime requestStartedAtUtc,
                                     LocalDateTime requestStartedAtLocal, LocalDateTime responseEndedAtUtc,
                                     LocalDateTime responseEndedAtLocal, String timezone, Long relativeMs) {
        TraceDataService.NetworkInput toInput() {
            return new TraceDataService.NetworkInput(url, method, statusCode, durationMs, failed, errorMessage,
                    requestSummary, responseSummary, requestStartedAtUtc, requestStartedAtLocal, responseEndedAtUtc,
                    responseEndedAtLocal, timezone, relativeMs);
        }
    }

    public record IssueClipRequest(Long traceSessionId, Long profileId, @NotBlank String clipScope, String title,
                                   String description, LocalDateTime clipStartAtUtc,
                                   LocalDateTime clipStartAtLocal, LocalDateTime clipEndAtUtc,
                                   LocalDateTime clipEndAtLocal, Long clipStartRelativeMs, Long clipEndRelativeMs,
                                   String timezone) {
        TraceDataService.IssueClipInput toInput() {
            return new TraceDataService.IssueClipInput(traceSessionId, profileId, clipScope, title, description,
                    clipStartAtUtc, clipStartAtLocal, clipEndAtUtc, clipEndAtLocal, clipStartRelativeMs,
                    clipEndRelativeMs, timezone);
        }
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<BrowserTraceSessionRecord> updateSession(@PathVariable Long sessionId,
                                                                  @Valid @RequestBody UpdateSessionRequest request,
                                                                  Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        return ApiResponse.ok(sessionService.updateName(sessionId, request.sessionName(), user));
    }

    public record BatchResult(int acceptedCount) {
    }

    // ---- 轨迹扫描学习 ----

    @PostMapping("/groups/{groupId}/scan")
    public ApiResponse<ScanResult> scanFromTrace(@PathVariable Long groupId,
                                                  Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        var group = groupService.getById(groupId, user);
        ScanResult result = traceAutoScanService.scanFromGroup(group.projectId(), groupId, user);
        return ApiResponse.ok(result);
    }

    public record UpdateSessionRequest(@NotBlank String sessionName) {
    }
}
