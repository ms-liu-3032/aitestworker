package com.company.aitest.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only review projections. Source tables remain the authority for every transition. */
@Service
public class AssetReviewInboxQuery {
    private final JdbcClient jdbc;

    public AssetReviewInboxQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String type, Long projectId, String status) {
        String reviewStatus = "REJECTED".equalsIgnoreCase(status) ? "REJECTED" : "PENDING";
        List<Map<String, Object>> result = new ArrayList<>();
        if (type == null || type.isBlank() || "wiki_pack".equals(type)) {
            result.addAll(listWikiPacks(projectId, reviewStatus));
        }
        if (type == null || type.isBlank() || "wiki_entry".equals(type)) {
            result.addAll(listWikiEntries(projectId, reviewStatus));
        }
        if (type == null || type.isBlank() || "automation".equals(type)) {
            result.addAll(listAutomation(projectId, reviewStatus));
        }
        return result;
    }

    private List<Map<String, Object>> listWikiPacks(Long projectId, String reviewStatus) {
        return jdbc.sql("""
                SELECT id, project_id, name, description, scope, source_type, promotion_source_pack_id, created_at
                FROM wiki_pack
                WHERE review_status = :reviewStatus
                  AND (:projectId = 0 OR project_id = :projectId)
                ORDER BY created_at DESC, id DESC
                """).param("reviewStatus", reviewStatus).param("projectId", projectId == null ? 0L : projectId)
                .query((rs, row) -> {
                    Map<String, Object> item = base("wiki_pack", rs.getLong("id"), rs.getLong("project_id"),
                            reviewStatus, rs.getString("created_at"));
                    item.put("name", rs.getString("name"));
                    item.put("description", rs.getString("description"));
                    item.put("scope", rs.getString("scope"));
                    item.put("sourceType", rs.getString("source_type"));
                    item.put("sourceId", rs.getObject("promotion_source_pack_id"));
                    return item;
                }).list();
    }

    private List<Map<String, Object>> listWikiEntries(Long projectId, String reviewStatus) {
        return jdbc.sql("""
                SELECT e.id, p.project_id, p.id pack_id, e.title, LEFT(e.content, 240) description,
                       e.source_type, e.source_ref_id, e.created_at
                FROM wiki_entry e JOIN wiki_pack p ON p.id = e.pack_id
                WHERE e.review_status = :reviewStatus
                  AND (:projectId = 0 OR p.project_id = :projectId)
                ORDER BY e.created_at DESC, e.id DESC
                """).param("reviewStatus", reviewStatus).param("projectId", projectId == null ? 0L : projectId)
                .query((rs, row) -> {
                    Map<String, Object> item = base("wiki_entry", rs.getLong("id"), rs.getLong("project_id"),
                            reviewStatus, rs.getString("created_at"));
                    item.put("name", rs.getString("title"));
                    item.put("description", rs.getString("description"));
                    item.put("packId", rs.getLong("pack_id"));
                    item.put("sourceType", rs.getString("source_type"));
                    item.put("sourceId", rs.getObject("source_ref_id"));
                    return item;
                }).list();
    }

    private List<Map<String, Object>> listAutomation(Long projectId, String reviewStatus) {
        return jdbc.sql("""
                SELECT a.id, a.project_id, a.case_id, a.asset_type, a.current_version,
                       a.created_at, a.status
                FROM automation_asset a JOIN automation_asset_version v
                  ON v.asset_id = a.id AND v.version_no = a.current_version
                WHERE a.case_kind = 'FORMAL' AND v.review_status = :reviewStatus
                  AND (:projectId = 0 OR a.project_id = :projectId)
                  AND (:reviewStatus = 'REJECTED' OR (a.status = 'VERIFIED' AND v.status = 'VERIFIED'))
                ORDER BY a.created_at DESC, a.id DESC
                """).param("reviewStatus", reviewStatus).param("projectId", projectId == null ? 0L : projectId)
                .query((rs, row) -> {
                    Map<String, Object> item = base("automation", rs.getLong("id"), rs.getLong("project_id"),
                            reviewStatus, rs.getString("created_at"));
                    item.put("name", "自动化资产 #" + rs.getLong("id"));
                    item.put("description", "已验证的" + rs.getString("asset_type") + "映射 v" + rs.getInt("current_version"));
                    item.put("sourceType", "FORMAL_CASE");
                    item.put("sourceId", rs.getLong("case_id"));
                    item.put("versionNo", rs.getInt("current_version"));
                    return item;
                }).list();
    }

    private static Map<String, Object> base(String type, long id, long projectId, String status, String createdAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("candidateType", type);
        item.put("id", id);
        item.put("projectId", projectId);
        item.put("status", status);
        item.put("createdAt", createdAt);
        return item;
    }
}
