package com.company.aitest.loop;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoopService {

    private static final Logger log = LoggerFactory.getLogger(LoopService.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public LoopService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    // ---- Feature Toggle ----

    public boolean isLoopEnabled() {
        Boolean enabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM system_feature_toggle WHERE feature_key = 'LOOP_ENGINE'",
                Boolean.class);
        return Boolean.TRUE.equals(enabled);
    }

    public void setLoopEnabled(boolean enabled, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO system_feature_toggle(feature_key, enabled, updated_by, updated_at)
                VALUES ('LOOP_ENGINE', ?, ?, ?)
                ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)
                """, enabled ? 1 : 0, user.id(), now);
    }

    // ---- Event CRUD ----

    public List<LoopEventRecord> listEvents(Long projectId) {
        return jdbc.sql("SELECT * FROM learning_loop_event WHERE project_id = ? ORDER BY created_at DESC")
                .param(projectId)
                .query(this::mapEvent).list();
    }

    @Transactional
    public LoopEventRecord recordEvent(Long projectId, String eventType, String sourceStage,
                                         String rawInput, String normalizedIssue, String suggestedAssetType,
                                         String sourceRefsJson, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO learning_loop_event(project_id, event_type, source_stage, raw_input,
                    normalized_issue, suggested_asset_type, source_refs_json, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """, projectId, eventType, sourceStage, rawInput, normalizedIssue, suggestedAssetType,
                sourceRefsJson, user.id(), now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getEvent(id);
    }

    public LoopEventRecord getEvent(Long eventId) {
        var results = jdbc.sql("SELECT * FROM learning_loop_event WHERE id = ?")
                .param(eventId)
                .query(this::mapEvent).list();
        if (results.isEmpty()) throw new BusinessException("事件不存在");
        return results.get(0);
    }

    @Transactional
    public LoopEventRecord updateEventStatus(Long eventId, String status) {
        validateEventStatus(status);
        LoopEventRecord event = getEvent(eventId);
        if (event.status().equals(status)) {
            return event;
        }
        if (!isAllowedLoopStatusTransition(event.status(), status)) {
            throw new BusinessException("事件状态不允许从 " + event.status() + " 变更为 " + status);
        }
        LocalDateTime now = timeProvider.now();
        int updated = jdbcTemplate.update("UPDATE learning_loop_event SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                status, now, eventId, event.status());
        if (updated == 0) {
            throw new BusinessException("事件状态已变化，请刷新后重试");
        }
        return getEvent(eventId);
    }

    // ---- Cluster CRUD ----

    public List<LoopClusterRecord> listClusters(Long projectId) {
        return jdbc.sql("SELECT * FROM learning_loop_cluster WHERE project_id = ? ORDER BY event_count DESC")
                .param(projectId)
                .query(this::mapCluster).list();
    }

    @Transactional
    public LoopClusterRecord createCluster(Long projectId, String theme, String suggestedAction,
                                            String targetAssetType, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO learning_loop_cluster(project_id, theme, event_count, suggested_action,
                    target_asset_type, status, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?, 'PENDING', ?, ?)
                """, projectId, theme, suggestedAction, targetAssetType, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getCluster(id);
    }

    public LoopClusterRecord getCluster(Long clusterId) {
        var results = jdbc.sql("SELECT * FROM learning_loop_cluster WHERE id = ?")
                .param(clusterId)
                .query(this::mapCluster).list();
        if (results.isEmpty()) throw new BusinessException("聚类不存在");
        return results.get(0);
    }

    @Transactional
    public LoopClusterRecord updateClusterStatus(Long clusterId, String status) {
        validateClusterStatus(status);
        LoopClusterRecord cluster = getCluster(clusterId);
        if (cluster.status().equals(status)) {
            return cluster;
        }
        if (!isAllowedLoopStatusTransition(cluster.status(), status)) {
            throw new BusinessException("聚类状态不允许从 " + cluster.status() + " 变更为 " + status);
        }
        LocalDateTime now = timeProvider.now();
        int updated = jdbcTemplate.update("UPDATE learning_loop_cluster SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                status, now, clusterId, cluster.status());
        if (updated == 0) {
            throw new BusinessException("聚类状态已变化，请刷新后重试");
        }
        return getCluster(clusterId);
    }

    // ---- Auto-clustering: group PENDING events by event_type ----

    @Transactional
    public List<LoopClusterRecord> autoCluster(Long projectId) {
        List<LoopClusterRecord> existing = listClusters(projectId);
        Set<String> existingThemes = existing.stream()
                .map(c -> c.theme() == null ? "" : c.theme())
                .collect(java.util.stream.Collectors.toSet());

        List<LoopEventRecord> pending = jdbc.sql(
                "SELECT * FROM learning_loop_event WHERE project_id = ? AND status = 'PENDING'")
                .param(projectId)
                .query(this::mapEvent).list();

        Map<String, List<LoopEventRecord>> byType = pending.stream()
                .collect(java.util.stream.Collectors.groupingBy(LoopEventRecord::eventType));

        List<LoopClusterRecord> created = new ArrayList<>();
        for (var entry : byType.entrySet()) {
            String eventType = entry.getKey();
            List<LoopEventRecord> events = entry.getValue();
            if (events.size() < 2) continue;
            if (existingThemes.contains(eventType)) {
                jdbcTemplate.update("""
                        UPDATE learning_loop_cluster SET event_count = event_count + ?, updated_at = ?
                        WHERE project_id = ? AND theme = ?
                        """, events.size(), timeProvider.now(), projectId, eventType);
            } else {
                created.add(createCluster(projectId, eventType,
                        events.size() + " 个同类事件待处理", guessTargetAsset(eventType), null));
            }
        }
        return created;
    }

    // ---- Candidate asset generation: promote approved clusters by asset type ----

    @Transactional
    public int generateCandidates(Long projectId) {
        List<LoopClusterRecord> approved = jdbc.sql(
                "SELECT * FROM learning_loop_cluster WHERE project_id = ? AND status = 'APPROVED' AND target_asset_type IS NOT NULL")
                .param(projectId)
                .query(this::mapCluster).list();

        int count = 0;
        for (var cluster : approved) {
            List<LoopEventRecord> events = jdbc.sql(
                    "SELECT * FROM learning_loop_event WHERE project_id = ? AND event_type = ? AND status = 'PENDING' LIMIT 5")
                    .param(projectId)
                    .param(cluster.theme())
                    .query(this::mapEvent).list();

            if (events.isEmpty()) continue;

            String assetType = cluster.targetAssetType();
            LocalDateTime now = timeProvider.now();
            String sourceRefs = buildSourceRefsJson(events);

            switch (assetType) {
                case "WIKI" -> count += generateWikiCandidates(projectId, cluster, events, now, sourceRefs);
                case "TOM" -> count += generateTomCandidates(projectId, cluster, events, now, sourceRefs);
                default -> count += generateWikiCandidates(projectId, cluster, events, now, sourceRefs);
            }

            int updated = jdbcTemplate.update(
                    "UPDATE learning_loop_cluster SET status = 'CONSUMED', updated_at = ? WHERE id = ? AND status = 'APPROVED'",
                    now, cluster.id());
            if (updated == 0) {
                throw new BusinessException("聚类状态已变化，请刷新后重试");
            }
        }
        return count;
    }

    private int generateWikiCandidates(Long projectId, LoopClusterRecord cluster,
                                        List<LoopEventRecord> events, LocalDateTime now, String sourceRefs) {
        String packName = "Loop:" + (cluster.theme() == null ? "未分类" : cluster.theme());
        jdbcTemplate.update("""
                INSERT INTO wiki_pack(project_id, scope, name, status, review_status, description, created_at, updated_at)
                VALUES (?, 'PROJECT', ?, 'DRAFT', 'PENDING', 'Loop 自动聚类生成', ?, ?)
                ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)
                """, projectId, packName, now, now);

        Long packId = jdbc.sql("SELECT id FROM wiki_pack WHERE project_id = ? AND name = ?")
                .param(projectId).param(packName)
                .query(Long.class).single();

        int count = 0;
        for (var evt : events) {
            if (evt.normalizedIssue() == null || evt.normalizedIssue().isBlank()) continue;
            String entryType = switch (evt.eventType()) {
                case "GENERATION_QUALITY" -> "RULE";
                case "TOM_STRATEGY" -> "DECISION";
                case "TRACE_SUMMARY_QUALITY" -> "HISTORY";
                case "LOCALIZATION_CHECK" -> "FAQ";
                default -> "RULE";
            };
            jdbcTemplate.update("""
                    INSERT INTO wiki_entry(pack_id, entry_type, title, content, source_refs_json,
                        review_status, confidence, effective_status, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', 0.7, 'ACTIVE', ?, ?, ?)
                    """, packId, entryType, cluster.theme() + " — 回灌建议",
                    buildEntryContent(evt), sourceRefs, evt.createdBy(), now, now);
            count++;
        }
        return count;
    }

    private int generateTomCandidates(Long projectId, LoopClusterRecord cluster,
                                       List<LoopEventRecord> events, LocalDateTime now, String sourceRefs) {
        for (var evt : events) {
            if (evt.normalizedIssue() == null || evt.normalizedIssue().isBlank()) continue;
            String tomSourceRefs = buildTomSourceRefsJson(cluster, evt);
            jdbcTemplate.update("""
                    INSERT INTO test_object_model(project_id, model_type, name, description, source_type,
                        source_refs_json, confidence, status, requires_human_confirm, created_by, created_at, updated_at)
                    VALUES (?, 'LOGIC', ?, ?, 'LOOP_CANDIDATE', ?, 0.7, 'CANDIDATE', 1, ?, ?, ?)
                    """, projectId, cluster.theme() + " — TOM候选",
                    evt.normalizedIssue(), tomSourceRefs,
                    evt.createdBy() == null ? 1L : evt.createdBy(), now, now);
        }
        return events.size();
    }

    private String buildTomSourceRefsJson(LoopClusterRecord cluster, LoopEventRecord evt) {
        return "{\"sourceType\":\"LOOP_CANDIDATE\""
                + ",\"clusterId\":" + cluster.id()
                + ",\"eventIds\":[" + evt.id() + "]"
                + ",\"eventType\":\"" + safeJson(evt.eventType()) + "\""
                + ",\"sourceStage\":\"" + safeJson(evt.sourceStage()) + "\""
                + ",\"normalizedIssue\":\"" + safeJson(evt.normalizedIssue()) + "\""
                + "}";
    }

    private String buildSourceRefsJson(List<LoopEventRecord> events) {
        var refs = new java.util.ArrayList<String>();
        for (var evt : events) {
            refs.add("{\"eventType\":\"" + safeJson(evt.eventType()) +
                    "\",\"eventId\":" + evt.id() +
                    ",\"sourceStage\":\"" + safeJson(evt.sourceStage()) + "\"}");
        }
        return "[" + String.join(",", refs) + "]";
    }

    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildEntryContent(LoopEventRecord evt) {
        StringBuilder sb = new StringBuilder();
        sb.append("来源: ").append(evt.eventType()).append("\n");
        sb.append("阶段: ").append(evt.sourceStage() == null ? "未知" : evt.sourceStage()).append("\n");
        sb.append("问题: ").append(evt.normalizedIssue()).append("\n");
        if (evt.rawInput() != null && !evt.rawInput().isBlank()) {
            String input = evt.rawInput().length() > 200 ? evt.rawInput().substring(0, 200) + "..." : evt.rawInput();
            sb.append("原始输入: ").append(input).append("\n");
        }
        return sb.toString();
    }

    // ---- Load Loop signals for main chain consumption ----

    public List<String> loadLoopHints(Long projectId) {
        if (!isLoopEnabled()) return List.of();

        List<String> hints = new ArrayList<>();
        List<LoopClusterRecord> clusters = jdbc.sql(
                "SELECT * FROM learning_loop_cluster WHERE project_id = ? AND status = 'APPROVED' ORDER BY event_count DESC LIMIT 10")
                .param(projectId)
                .query(this::mapCluster).list();

        for (var c : clusters) {
            String hint = "[" + (c.theme() == null ? "未知" : c.theme()) + "]";
            if (c.suggestedAction() != null) hint += " " + c.suggestedAction();
            hints.add(hint);
        }
        return hints;
    }

    private String guessTargetAsset(String eventType) {
        return switch (eventType) {
            case "GENERATION_QUALITY" -> "WIKI";
            case "TOM_STRATEGY" -> "TOM";
            case "TRACE_SUMMARY_QUALITY" -> "WIKI";
            case "LOCALIZATION_CHECK" -> "WIKI";
            default -> "WIKI";
        };
    }

    private void validateEventStatus(String status) {
        if (!Set.of("PENDING", "APPROVED", "REJECTED", "CONSUMED").contains(status)) {
            throw new BusinessException("事件状态不支持：" + status);
        }
    }

    private void validateClusterStatus(String status) {
        if (!Set.of("PENDING", "APPROVED", "REJECTED", "CONSUMED").contains(status)) {
            throw new BusinessException("聚类状态不支持：" + status);
        }
    }

    private boolean isAllowedLoopStatusTransition(String from, String to) {
        if ("PENDING".equals(from)) {
            return "APPROVED".equals(to) || "REJECTED".equals(to);
        }
        if ("APPROVED".equals(from)) {
            return "CONSUMED".equals(to) || "REJECTED".equals(to);
        }
        return false;
    }

    // ---- Mapper ----

    private LoopEventRecord mapEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LoopEventRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("event_type"),
                rs.getString("source_stage"),
                rs.getString("raw_input"),
                rs.getString("normalized_issue"),
                rs.getString("suggested_asset_type"),
                rs.getString("source_refs_json"),
                rs.getString("status"),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private LoopClusterRecord mapCluster(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LoopClusterRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("theme"),
                rs.getInt("event_count"),
                rs.getString("suggested_action"),
                rs.getString("target_asset_type"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
