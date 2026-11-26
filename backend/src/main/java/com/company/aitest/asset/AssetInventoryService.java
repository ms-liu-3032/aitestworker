package com.company.aitest.asset;

import java.util.List;

import com.company.aitest.common.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AssetInventoryService {
    private static final String INVENTORY_SQL = """
            SELECT 'TOM_NODE' asset_type, scope, status, source_type, COUNT(*) item_count
            FROM test_object_model WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY scope, status, source_type
            UNION ALL
            SELECT 'TOM_RELATION', 'PROJECT', status, source_type, COUNT(*)
            FROM test_object_model_relation WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY status, source_type
            UNION ALL
            SELECT 'TOM_ALIAS', 'PROJECT', status, source_type, COUNT(*)
            FROM tom_alias WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY status, source_type
            UNION ALL
            SELECT 'TOM_IMPACT', 'PROJECT', status, source_type, COUNT(*)
            FROM tom_impact_edge WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY status, source_type
            UNION ALL
            SELECT 'WIKI_PACK', scope, CONCAT(status, '/', review_status), source_type, COUNT(*)
            FROM wiki_pack WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY scope, status, review_status, source_type
            UNION ALL
            SELECT 'WIKI_ENTRY', wp.scope,
                   CONCAT(we.effective_status, '/', we.review_status), we.source_type, COUNT(*)
            FROM wiki_entry we JOIN wiki_pack wp ON wp.id = we.pack_id
            WHERE (:projectId = 0 OR wp.project_id = :projectId)
            GROUP BY wp.scope, we.effective_status, we.review_status, we.source_type
            UNION ALL
            SELECT 'FORMAL_CASE', case_scope COLLATE utf8mb4_unicode_ci,
                   case_status COLLATE utf8mb4_unicode_ci,
                   import_source_type COLLATE utf8mb4_unicode_ci, COUNT(*)
            FROM test_case_asset WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY case_scope, case_status, import_source_type
            UNION ALL
            SELECT 'PERSONAL_CASE', case_scope COLLATE utf8mb4_unicode_ci,
                   case_status COLLATE utf8mb4_unicode_ci, 'TRACE_OR_AGENT', COUNT(*)
            FROM trace_generated_case WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY case_scope, case_status
            UNION ALL
            SELECT 'AUTOMATION', a.scope, CONCAT(a.status, '/', v.review_status), a.case_kind, COUNT(*)
            FROM automation_asset a JOIN automation_asset_version v
              ON v.asset_id = a.id AND v.version_no = a.current_version
            WHERE (:projectId = 0 OR a.project_id = :projectId)
            GROUP BY a.scope, a.status, v.review_status, a.case_kind
            UNION ALL
            SELECT 'AUTOMATION_VERSION', a.scope,
                   CONCAT(v.status, '/', v.review_status), a.case_kind, COUNT(*)
            FROM automation_asset_version v JOIN automation_asset a ON a.id = v.asset_id
            WHERE (:projectId = 0 OR a.project_id = :projectId)
            GROUP BY a.scope, v.status, v.review_status, a.case_kind
            UNION ALL
            SELECT 'BUSINESS_PACK', 'PROJECT', status, pack_type, COUNT(*)
            FROM business_pack WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY status, pack_type
            UNION ALL
            SELECT 'BUSINESS_PACK_ITEM', 'PROJECT', status, source_type, COUNT(*)
            FROM business_pack_item WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY status, source_type
            UNION ALL
            SELECT 'KNOWLEDGE_ASSET', visibility COLLATE utf8mb4_unicode_ci,
                   status COLLATE utf8mb4_unicode_ci,
                   asset_ref_type COLLATE utf8mb4_unicode_ci, COUNT(*)
            FROM knowledge_asset WHERE (:projectId = 0 OR project_id = :projectId)
            GROUP BY visibility, status, asset_ref_type
            ORDER BY asset_type, scope, status, source_type
            """;

    private final JdbcClient jdbc;

    public AssetInventoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<InventoryRow> list(Long projectId) {
        if (projectId != null && projectId <= 0) throw new BusinessException("项目编号必须大于 0");
        return jdbc.sql(INVENTORY_SQL)
                .param("projectId", projectId == null ? 0L : projectId)
                .query((rs, rowNum) -> new InventoryRow(
                        rs.getString("asset_type"), rs.getString("scope"),
                        rs.getString("status"), rs.getString("source_type"),
                        rs.getLong("item_count")))
                .list();
    }

    public record InventoryRow(String assetType, String scope, String status,
                               String sourceType, long count) {}
}
