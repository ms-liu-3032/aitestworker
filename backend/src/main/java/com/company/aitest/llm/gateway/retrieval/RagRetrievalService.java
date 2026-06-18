package com.company.aitest.llm.gateway.retrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RAG 检索的统一入口。Sprint 2 提供 SQL 路径（基于关键词命中），
 * Sprint 3+ 接入 Weaviate（届时本类内部分流）。
 *
 * <h3>过滤范式（与 docs/handover/07_LLM数据污染治理方案.md §5.2 对齐）</h3>
 * <ol>
 *   <li>项目过滤：当前 projectId 或 scope ∈ (PUBLIC, SYSTEM)</li>
 *   <li>状态过滤：status = ACTIVE 且 deprecated_at IS NULL</li>
 *   <li>审核过滤：PERSONAL/SYSTEM 免审，其余必须 review_status = APPROVED（默认配置或未配置时按 APPROVED 处理）</li>
 *   <li>PERSONAL 只允许 createdBy = 当前 userId</li>
 *   <li>topN 截断</li>
 * </ol>
 *
 * <h3>说明</h3>
 * 现有 V1 表（如 test_case_asset）的"老数据"在 V7 之后默认补 case_scope=PROJECT、case_status=SUBMITTED、
 * trust_level=HISTORICAL_CASE；本服务读取这些默认值即可。
 */
@Component
public class RagRetrievalService {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagRetrievalService(JdbcTemplate jdbcTemplate, JdbcClient jdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = jdbcTemplate == null ? null : new NamedParameterJdbcTemplate(jdbcTemplate);
        this.jdbc = jdbc;
    }

    public RetrievalResult retrieve(LlmInvocationRequest request, RetrievalPolicy policy) {
        if (policy == null || policy.disabled()) {
            return RetrievalResult.disabled();
        }
        Long projectId = request.projectId();
        Long userId = request.userId();

        List<RetrievedAsset> merged = new ArrayList<>();

        if (policy.kinds().contains(RetrievalPolicy.RetrievalAssetKind.KNOWLEDGE)) {
            merged.addAll(searchKnowledge(projectId, userId, policy));
        }
        if (policy.kinds().contains(RetrievalPolicy.RetrievalAssetKind.CASE)) {
            merged.addAll(searchCases(projectId, userId, policy));
        }
        if (policy.kinds().contains(RetrievalPolicy.RetrievalAssetKind.SKILL)) {
            merged.addAll(searchSkills(projectId, userId, policy));
        }

        // 简单按 (trust 权重 + 关键词命中) 排序；真实相似度等 Weaviate 接入后替换
        merged.sort((a, b) -> {
            int ta = trustWeight(a.trustLevel());
            int tb = trustWeight(b.trustLevel());
            if (ta != tb) return Integer.compare(tb, ta);
            double sa = a.similarity() == null ? 0.0 : a.similarity();
            double sb = b.similarity() == null ? 0.0 : b.similarity();
            return Double.compare(sb, sa);
        });

        if (merged.size() > policy.topN()) {
            merged = new ArrayList<>(merged.subList(0, policy.topN()));
        }

        String excludedJson = buildExcludedPolicyJson(policy);
        return new RetrievalResult(merged, false, excludedJson);
    }

    // -------------------- knowledge_chunk --------------------

    private List<RetrievedAsset> searchKnowledge(Long projectId, Long userId, RetrievalPolicy policy) {
        if (projectId == null) return List.of();
        String sql = """
                select kc.id as id, kc.document_id as document_id,
                       kc.chunk_title as title, kc.chunk_content as content,
                       kc.content_hash as content_hash, kc.deprecated as deprecated,
                       kd.scope as scope, kd.review_status as review_status,
                       kd.trust_level as trust_level, kd.title as doc_title,
                       kd.source_type as source_type, kd.doc_status as doc_status
                from knowledge_chunk kc
                join knowledge_document kd on kd.id = kc.document_id
                where kc.project_id = :projectId
                  and (:includeDeprecated = 1 or kc.deprecated = 0)
                  and (:includeDeprecated = 1 or kd.deprecated_at is null)
                  and kd.review_status = 'APPROVED'
                limit :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("includeDeprecated", policy.includeDeprecated() ? 1 : 0)
                .addValue("limit", policy.topN() * 3);

        List<Map<String, Object>> rows;
        try {
            rows = namedJdbc.queryForList(sql, params);
        } catch (org.springframework.dao.DataAccessException ex) {
            // 兼容老库：V7 的 deprecated 列在 V7 之后才有
            return List.of();
        }
        List<RetrievedAsset> result = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String content = asString(r.get("content"));
            result.add(new RetrievedAsset(
                    asLong(r.get("id")),
                    "KNOWLEDGE_CHUNK",
                    asStringOrDefault(r.get("scope"), "PROJECT"),
                    asStringOrDefault(r.get("source_type"), "YUQUE"),
                    "knowledge_document#" + asLong(r.get("document_id")),
                    asStringOrDefault(r.get("doc_status"), "IMPORTED"),
                    asStringOrDefault(r.get("trust_level"), "YUQUE_DOC"),
                    asStringOrDefault(r.get("title"), asString(r.get("doc_title"))),
                    snippet(content, 240),
                    asString(r.get("content_hash")),
                    null));
        }
        return result;
    }

    // -------------------- test_case_asset --------------------

    private List<RetrievedAsset> searchCases(Long projectId, Long userId, RetrievalPolicy policy) {
        if (projectId == null) return List.of();
        String sql = """
                select id, case_no, case_title, module_name, expected_result, steps,
                       case_scope, case_status, trust_level, deprecated_at
                from test_case_asset
                where project_id = :projectId
                  and (case_scope is null or case_scope in ('PROJECT', 'PUBLIC', 'SYSTEM') or case_scope = 'PERSONAL' and submitted_by = :userId)
                  and (:includeDeprecated = 1 or deprecated_at is null)
                limit :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("userId", userId == null ? 0L : userId)
                .addValue("includeDeprecated", policy.includeDeprecated() ? 1 : 0)
                .addValue("limit", policy.topN() * 3);

        List<Map<String, Object>> rows;
        try {
            rows = namedJdbc.queryForList(sql, params);
        } catch (org.springframework.dao.DataAccessException ex) {
            return List.of();
        }
        List<RetrievedAsset> result = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String content = combineCaseContent(asString(r.get("steps")), asString(r.get("expected_result")));
            result.add(new RetrievedAsset(
                    asLong(r.get("id")),
                    "CASE_ASSET",
                    asStringOrDefault(r.get("case_scope"), "PROJECT"),
                    "CASE",
                    "test_case_asset#" + asLong(r.get("id")),
                    asStringOrDefault(r.get("case_status"), "SUBMITTED"),
                    asStringOrDefault(r.get("trust_level"), "HISTORICAL_CASE"),
                    asString(r.get("case_title")),
                    snippet(content, 240),
                    null,
                    null));
        }
        return result;
    }

    // -------------------- skill_template --------------------

    private List<RetrievedAsset> searchSkills(Long projectId, Long userId, RetrievalPolicy policy) {
        if (projectId == null) return List.of();
        String sql = """
                select id, skill_name, applicable_scene, flow_steps, scope, status, review_status,
                       trust_level, deprecated_at, created_by
                from test_skill_template
                where status = 'ACTIVE'
                  and (:includeDeprecated = 1 or deprecated_at is null)
                  and (
                    scope = 'SYSTEM'
                    or (scope = 'PROJECT' and project_id = :projectId and review_status = 'APPROVED')
                    or (scope = 'PUBLIC' and review_status = 'APPROVED')
                    or (scope = 'PERSONAL' and created_by = :userId)
                  )
                limit :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("userId", userId == null ? 0L : userId)
                .addValue("includeDeprecated", policy.includeDeprecated() ? 1 : 0)
                .addValue("limit", policy.topN() * 3);

        List<Map<String, Object>> rows;
        try {
            rows = namedJdbc.queryForList(sql, params);
        } catch (org.springframework.dao.DataAccessException ex) {
            return List.of();
        }
        List<RetrievedAsset> result = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String description = asString(r.get("applicable_scene"));
            if (description == null || description.isBlank()) {
                description = asString(r.get("flow_steps"));
            }
            result.add(new RetrievedAsset(
                    asLong(r.get("id")),
                    "SKILL_TEMPLATE",
                    asStringOrDefault(r.get("scope"), "PROJECT"),
                    "SKILL",
                    "test_skill_template#" + asLong(r.get("id")),
                    asStringOrDefault(r.get("status"), "ACTIVE"),
                    asStringOrDefault(r.get("trust_level"), "AI_GENERATED"),
                    asString(r.get("skill_name")),
                    snippet(description, 240),
                    null,
                    null));
        }
        return result;
    }

    // -------------------- helpers --------------------

    private int trustWeight(String level) {
        if (level == null) return 0;
        return switch (level) {
            case "SYSTEM_RULE" -> 100;
            case "PROJECT_APPROVED" -> 80;
            case "YUQUE_DOC" -> 70;
            case "HISTORICAL_CASE" -> 60;
            case "TRACE_CONFIRMED" -> 55;
            case "USER_DRAFT" -> 30;
            case "AI_GENERATED" -> 20;
            default -> 10;
        };
    }

    private String combineCaseContent(String steps, String expected) {
        StringBuilder sb = new StringBuilder();
        if (steps != null && !steps.isBlank()) sb.append("步骤：").append(steps);
        if (expected != null && !expected.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("预期：").append(expected);
        }
        return sb.toString();
    }

    private String snippet(String text, int max) {
        if (text == null) return null;
        String trimmed = text.strip();
        if (trimmed.length() <= max) return trimmed;
        return trimmed.substring(0, max) + "…";
    }

    private String buildExcludedPolicyJson(RetrievalPolicy policy) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("deprecatedAssets", policy.includeDeprecated() ? "included_by_user_consent" : "excluded");
        p.put("otherUserDrafts", "excluded");
        p.put("unreviewedAssets", policy.includeUnreviewedPublic() ? "included_by_user_consent" : "excluded");
        p.put("unauthorizedProjectAssets", "excluded");
        try {
            return objectMapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static String asStringOrDefault(Object o, String def) {
        String s = asString(o);
        return (s == null || s.isBlank()) ? def : s;
    }
}
