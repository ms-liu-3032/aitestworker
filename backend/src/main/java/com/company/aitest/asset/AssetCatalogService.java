package com.company.aitest.asset;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.company.aitest.common.BusinessException;
import com.company.aitest.project.PageResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** One read model over the existing asset owners. No content or approval state is copied. */
@Service
public class AssetCatalogService {
    private static final Set<String> TYPES = Set.of("TOM", "WIKI_PACK", "WIKI_ENTRY", "FORMAL_CASE",
            "AUTOMATION", "BUSINESS_PACK", "KNOWLEDGE", "SKILL", "TOOL", "TRACE_SUMMARY");
    private static final String UNION_SQL = """
            SELECT 'TOM' asset_type, id, project_id, name COLLATE utf8mb4_unicode_ci title,
                   scope COLLATE utf8mb4_unicode_ci scope,
                   status COLLATE utf8mb4_unicode_ci lifecycle_status,
                   CASE WHEN status = 'CANDIDATE' THEN 'PENDING'
                        WHEN status = 'REJECTED' THEN 'REJECTED'
                        WHEN status IN ('CONFIRMED', 'ACTIVE') THEN 'APPROVED' ELSE NULL END review_status,
                   source_type COLLATE utf8mb4_unicode_ci source_type, source_ref_id source_id,
                   NULL parent_id, created_at
              FROM test_object_model
            UNION ALL
            SELECT 'WIKI_PACK', id, project_id, name COLLATE utf8mb4_unicode_ci,
                   scope COLLATE utf8mb4_unicode_ci, status COLLATE utf8mb4_unicode_ci,
                   review_status COLLATE utf8mb4_unicode_ci,
                   source_type COLLATE utf8mb4_unicode_ci, promotion_source_pack_id,
                   NULL, created_at FROM wiki_pack
            UNION ALL
            SELECT 'WIKI_ENTRY', e.id, p.project_id, e.title COLLATE utf8mb4_unicode_ci,
                   p.scope COLLATE utf8mb4_unicode_ci,
                   e.effective_status COLLATE utf8mb4_unicode_ci,
                   e.review_status COLLATE utf8mb4_unicode_ci,
                   e.source_type COLLATE utf8mb4_unicode_ci, e.source_ref_id, e.pack_id, e.created_at
              FROM wiki_entry e JOIN wiki_pack p ON p.id = e.pack_id
            UNION ALL
            SELECT 'FORMAL_CASE', id, project_id, case_title COLLATE utf8mb4_unicode_ci,
                   case_scope COLLATE utf8mb4_unicode_ci, case_status COLLATE utf8mb4_unicode_ci,
                   NULL, import_source_type COLLATE utf8mb4_unicode_ci, import_source_ref_id,
                   NULL, created_at FROM test_case_asset
            UNION ALL
            SELECT 'AUTOMATION', a.id, a.project_id, CONCAT('自动化映射 #', a.id) COLLATE utf8mb4_unicode_ci,
                   a.scope COLLATE utf8mb4_unicode_ci, a.status COLLATE utf8mb4_unicode_ci,
                   v.review_status COLLATE utf8mb4_unicode_ci,
                   a.case_kind COLLATE utf8mb4_unicode_ci, a.case_id, NULL, a.created_at
              FROM automation_asset a JOIN automation_asset_version v
                ON v.asset_id = a.id AND v.version_no = a.current_version
             WHERE a.case_kind = 'FORMAL'
            UNION ALL
            SELECT 'BUSINESS_PACK', id, project_id, pack_name COLLATE utf8mb4_unicode_ci,
                   'PROJECT', status COLLATE utf8mb4_unicode_ci,
                   NULL, pack_type COLLATE utf8mb4_unicode_ci, NULL, NULL, created_at FROM business_pack
            UNION ALL
            SELECT 'KNOWLEDGE', id, project_id, title COLLATE utf8mb4_unicode_ci,
                   visibility COLLATE utf8mb4_unicode_ci, status COLLATE utf8mb4_unicode_ci,
                   NULL, asset_ref_type COLLATE utf8mb4_unicode_ci, asset_ref_id, NULL, created_at FROM knowledge_asset
             WHERE visibility <> 'PRIVATE'
            UNION ALL
            SELECT 'SKILL', id, project_id, skill_name COLLATE utf8mb4_unicode_ci,
                   scope COLLATE utf8mb4_unicode_ci, status COLLATE utf8mb4_unicode_ci,
                   review_status COLLATE utf8mb4_unicode_ci,
                   source_type COLLATE utf8mb4_unicode_ci, source_id, NULL, created_at FROM test_skill_template
             WHERE scope <> 'PERSONAL'
            UNION ALL
            SELECT 'TOOL', id, project_id, tool_name COLLATE utf8mb4_unicode_ci,
                   scope COLLATE utf8mb4_unicode_ci, status COLLATE utf8mb4_unicode_ci,
                   review_status COLLATE utf8mb4_unicode_ci,
                   source_type COLLATE utf8mb4_unicode_ci, source_id, NULL, created_at FROM test_tool_template
             WHERE scope <> 'PERSONAL'
            UNION ALL
            SELECT 'TRACE_SUMMARY', id, project_id, overview COLLATE utf8mb4_unicode_ci,
                   scope COLLATE utf8mb4_unicode_ci, status COLLATE utf8mb4_unicode_ci,
                   review_status COLLATE utf8mb4_unicode_ci,
                   'TRACE_GROUP', trace_group_id, NULL, created_at FROM trace_summary
             WHERE scope <> 'PERSONAL'
            """;

    private final JdbcClient jdbc;

    public AssetCatalogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PageResult<AssetRow> list(Long projectId, String type, String status, String keyword, int page, int size) {
        if (projectId != null && projectId <= 0) throw new BusinessException("项目编号必须大于 0");
        if (type != null && !type.isBlank() && !TYPES.contains(type)) throw new BusinessException("不支持的资产类型");
        if (page < 1 || size < 1 || size > 100) throw new BusinessException("页码或每页数量无效（每页最多 100 条）");
        String term = keyword == null ? "" : keyword.trim();
        if (term.length() > 100) throw new BusinessException("关键词不能超过 100 字");
        String filter = " WHERE (:projectId = 0 OR project_id = :projectId)"
                + " AND (:type = '' OR asset_type = :type)"
                + " AND (:status = '' OR lifecycle_status = :status OR review_status = :status)"
                + " AND (:keyword = '' OR title LIKE :keyword)";
        String source = " FROM (" + UNION_SQL + ") catalog";
        var params = java.util.Map.<String, Object>of(
                "projectId", projectId == null ? 0L : projectId,
                "type", type == null ? "" : type,
                "status", status == null ? "" : status,
                "keyword", term.isEmpty() ? "" : "%" + term + "%");
        long count = jdbc.sql("SELECT COUNT(*)" + source + filter).params(params).query(Long.class).single();
        List<AssetRow> rows = jdbc.sql("SELECT *" + source + filter + " ORDER BY created_at DESC, asset_type, id DESC LIMIT :size OFFSET :offset")
                .params(params).param("size", size).param("offset", (long) (page - 1) * size)
                .query((rs, row) -> new AssetRow(
                        rs.getString("asset_type"), rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("title"), rs.getString("scope"), rs.getString("lifecycle_status"),
                        rs.getString("review_status"), rs.getString("source_type"),
                        rs.getObject("source_id", Long.class), rs.getObject("parent_id", Long.class),
                        rs.getTimestamp("created_at") == null
                                ? null : rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
        return new PageResult<>(rows, Math.toIntExact(count), page, size);
    }

    public record AssetRow(String assetType, long id, long projectId, String title, String scope,
                           String lifecycleStatus, String reviewStatus, String sourceType,
                           Long sourceId, Long parentId, LocalDateTime createdAt) {}
}
