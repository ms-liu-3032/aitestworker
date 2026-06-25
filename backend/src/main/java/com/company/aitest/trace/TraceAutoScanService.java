package com.company.aitest.trace;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.scan.ControlledScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TraceAutoScanService {
    private static final Logger log = LoggerFactory.getLogger(TraceAutoScanService.class);
    static final String TRACE_AUTO_SCAN_SOURCE_KEY = "TRACE_AUTO_SCAN";
    static final String TRACE_AUTO_SCAN_SOURCE_LABEL = "轨迹自动补扫";

    private final JdbcClient jdbc;
    private final ControlledScanService controlledScanService;

    public TraceAutoScanService(JdbcClient jdbc, ControlledScanService controlledScanService) {
        this.jdbc = jdbc;
        this.controlledScanService = controlledScanService;
    }

    public void scheduleSessionAutoScan(BrowserTraceSessionRecord session, CurrentUser user) {
        if (session == null || user == null || user.id() == null) {
            return;
        }
        CompletableFuture.runAsync(() -> runSessionAutoScan(session, user))
                .exceptionally(ex -> null);
    }

    /**
     * 从轨迹组的所有会话中扫描学习，生成页面画像。
     * 返回扫描结果供前端展示。
     */
    public ScanResult scanFromGroup(Long projectId, Long groupId, CurrentUser user) {
        // 1. 加载该组所有会话的事件
        List<Long> sessionIds = jdbc.sql(
                "SELECT id FROM browser_trace_session WHERE trace_group_id = :groupId")
                .param("groupId", groupId).query(Long.class).list();

        List<TraceEventSnapshot> allEvents = new ArrayList<>();
        for (Long sessionId : sessionIds) {
            allEvents.addAll(loadSessionEvents(sessionId, user));
        }

        if (allEvents.isEmpty()) {
            return new ScanResult(0, 0, List.of());
        }

        // 2. 构建页面画像草稿
        List<ControlledScanService.PageProfileDraft> drafts = buildDraftsFromEvents(allEvents);
        if (drafts.isEmpty()) {
            return new ScanResult(0, allEvents.size(), List.of());
        }

        // 3. Upsert 页面画像
        int profileCount = controlledScanService.upsertProfiles(projectId, drafts, user);

        // 4. 收集扫描到的页面列表
        List<String> scannedPages = drafts.stream()
                .map(ControlledScanService.PageProfileDraft::pageLabel)
                .distinct()
                .toList();

        log.info("轨迹组 {} 扫描完成：{} 事件 → {} 页面画像 → {} 页面",
                groupId, allEvents.size(), drafts.size(), scannedPages.size());

        return new ScanResult(profileCount, allEvents.size(), scannedPages);
    }

    static record ScanResult(int profileCount, int eventCount, List<String> scannedPages) {
    }

    void runSessionAutoScan(BrowserTraceSessionRecord session, CurrentUser user) {
        List<TraceEventSnapshot> events = loadSessionEvents(session.id(), user);
        if (events.isEmpty()) {
            return;
        }
        List<ControlledScanService.PageProfileDraft> drafts = buildDraftsFromEvents(events);
        if (drafts.isEmpty()) {
            return;
        }
        controlledScanService.upsertProfiles(session.projectId(), drafts, user);
    }

    List<TraceEventSnapshot> loadSessionEvents(Long sessionId, CurrentUser user) {
        return jdbc.sql("""
                select page_url, page_title, event_type, element_text, element_role,
                       value_summary, section_title, dialog_title, object_label
                from browser_trace_event
                where trace_session_id = :sessionId
                  and page_url is not null
                  and page_url <> ''
                order by relative_ms asc, id asc
                """)
                .param("sessionId", sessionId)
                .query((RowMapper<TraceEventSnapshot>) (rs, rowNum) -> new TraceEventSnapshot(
                        rs.getString("page_url"),
                        rs.getString("page_title"),
                        rs.getString("event_type"),
                        rs.getString("element_text"),
                        rs.getString("element_role"),
                        rs.getString("value_summary"),
                        rs.getString("section_title"),
                        rs.getString("dialog_title"),
                        rs.getString("object_label")
                ))
                .list();
    }

    static List<ControlledScanService.PageProfileDraft> buildDraftsFromEvents(List<TraceEventSnapshot> events) {
        Map<String, DraftAccumulator> byRoute = new LinkedHashMap<>();
        for (TraceEventSnapshot event : events) {
            String pageUrl = cleanText(event.pageUrl());
            String routePath = normalizeRoutePath(pageUrl);
            if (routePath == null) {
                continue;
            }
            DraftAccumulator acc = byRoute.computeIfAbsent(routePath, ignored -> new DraftAccumulator(pageUrl, routePath));
            acc.seePageUrl(pageUrl);
            acc.seePageTitle(event.pageTitle());
            acc.addHeading(event.pageTitle());
            acc.addHeading(event.sectionTitle());
            acc.addDialog(event.dialogTitle());
            acc.addPreview(event.pageTitle());
            acc.addPreview(event.sectionTitle());
            acc.addPreview(event.dialogTitle());
            acc.addPreview(event.objectLabel());
            acc.addPreview(event.elementText());

            String eventType = cleanText(event.eventType());
            if (eventType == null) {
                continue;
            }
            switch (eventType.toUpperCase(Locale.ROOT)) {
                case "PAGE_OPEN", "NAVIGATION" -> {
                    acc.addHeading(event.pageTitle());
                }
                case "INPUT", "CHANGE", "BLUR" -> {
                    acc.addField(event.objectLabel());
                    acc.addField(event.elementText());
                    acc.addField(event.valueSummary());
                }
                case "CLICK", "SUBMIT" -> {
                    acc.addAction(event.objectLabel());
                    acc.addAction(event.elementText());
                }
                default -> {
                    if ("button".equalsIgnoreCase(cleanText(event.elementRole()))) {
                        acc.addAction(event.objectLabel());
                        acc.addAction(event.elementText());
                    }
                }
            }
        }
        return byRoute.values().stream()
                .map(DraftAccumulator::toDraft)
                .filter(Objects::nonNull)
                .toList();
    }

    private static String normalizeRoutePath(String pageUrl) {
        String url = cleanText(pageUrl);
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            String query = cleanText(uri.getQuery());
            String fragment = cleanText(uri.getFragment());
            String route = query == null ? path : path + "?" + query;
            return fragment == null ? route : route + "#" + fragment;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String shortenPageTitle(String value) {
        String text = cleanText(value);
        if (text == null) {
            return null;
        }
        for (String separator : List.of(" - ", " | ", "_")) {
            int index = text.indexOf(separator);
            if (index > 0) {
                text = text.substring(0, index).trim();
                break;
            }
        }
        return text.isBlank() ? null : text;
    }

    private static String inferLabelFromRoute(String routePath) {
        if (routePath == null || routePath.isBlank()) {
            return null;
        }
        String normalized = routePath.contains("?") ? routePath.substring(0, routePath.indexOf('?')) : routePath;
        int hashIndex = normalized.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex + 1 < normalized.length()) {
            normalized = normalized.substring(hashIndex + 1);
        }
        String[] parts = normalized.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = cleanText(parts[i]);
            if (part != null) {
                return part.replace('-', ' ').replace('_', ' ');
            }
        }
        return null;
    }

    static record TraceEventSnapshot(
            String pageUrl,
            String pageTitle,
            String eventType,
            String elementText,
            String elementRole,
            String valueSummary,
            String sectionTitle,
            String dialogTitle,
            String objectLabel) {
    }

    private static final class DraftAccumulator {
        private static final int LIMIT = 20;

        private final String routePath;
        private String pageUrl;
        private String pageTitle;
        private final Set<String> headings = new LinkedHashSet<>();
        private final Set<String> fields = new LinkedHashSet<>();
        private final Set<String> actions = new LinkedHashSet<>();
        private final Set<String> dialogs = new LinkedHashSet<>();
        private final Set<String> preview = new LinkedHashSet<>();

        private DraftAccumulator(String pageUrl, String routePath) {
            this.pageUrl = pageUrl;
            this.routePath = routePath;
        }

        private void seePageUrl(String value) {
            String text = cleanText(value);
            if (text != null) {
                this.pageUrl = text;
            }
        }

        private void seePageTitle(String value) {
            String text = shortenPageTitle(value);
            if (text != null) {
                this.pageTitle = text;
            }
        }

        private void addHeading(String value) {
            addLimited(headings, shortenPageTitle(value));
        }

        private void addField(String value) {
            addLimited(fields, normalizeLabel(value));
        }

        private void addAction(String value) {
            addLimited(actions, normalizeLabel(value));
        }

        private void addDialog(String value) {
            addLimited(dialogs, normalizeLabel(value));
        }

        private void addPreview(String value) {
            addLimited(preview, normalizePreview(value));
        }

        private ControlledScanService.PageProfileDraft toDraft() {
            String pageLabel = firstNonBlank(
                    pageTitle,
                    headings.stream().findFirst().orElse(null),
                    dialogs.stream().findFirst().orElse(null),
                    inferLabelFromRoute(routePath));
            if (pageLabel == null) {
                return null;
            }
            String bodyPreview = preview.isEmpty() ? null : String.join("；", preview);
            if (bodyPreview != null && bodyPreview.length() > 300) {
                bodyPreview = bodyPreview.substring(0, 300);
            }
            return new ControlledScanService.PageProfileDraft(
                    TRACE_AUTO_SCAN_SOURCE_KEY,
                    TRACE_AUTO_SCAN_SOURCE_LABEL,
                    pageLabel,
                    pageUrl,
                    routePath,
                    pageTitle,
                    null,
                    new ArrayList<>(headings),
                    new ArrayList<>(fields),
                    new ArrayList<>(actions),
                    new ArrayList<>(dialogs),
                    bodyPreview
            );
        }

        private static void addLimited(Set<String> set, String value) {
            if (value == null || set.size() >= LIMIT) {
                return;
            }
            set.add(value);
        }

        private static String normalizeLabel(String value) {
            String text = cleanText(value);
            if (text == null) {
                return null;
            }
            if (text.length() > 64) {
                text = text.substring(0, 64);
            }
            return text;
        }

        private static String normalizePreview(String value) {
            String text = cleanText(value);
            if (text == null) {
                return null;
            }
            if (text.length() > 120) {
                text = text.substring(0, 120);
            }
            return text;
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                String text = cleanText(value);
                if (text != null) {
                    return text;
                }
            }
            return null;
        }
    }
}
