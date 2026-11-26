package com.company.aitest.wiki;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

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
public class WikiService {

    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public WikiService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    // ---- Wiki Pack CRUD ----

    public List<WikiPackRecord> listPacks(Long projectId) {
        return jdbc.sql("SELECT * FROM wiki_pack WHERE project_id = ? AND scope = 'PROJECT' ORDER BY created_at DESC")
                .param(projectId)
                .query(this::mapPack).list();
    }

    public List<WikiPackRecord> listPromotions(Long sourcePackId) {
        return jdbc.sql("""
                SELECT * FROM wiki_pack WHERE promotion_source_pack_id = ?
                ORDER BY id DESC
                """).param(sourcePackId).query(this::mapPack).list();
    }

    /** A promotion copies only reviewed effective entries into a new, entirely unreviewed candidate. */
    @Transactional
    public WikiPackRecord submitPromotion(Long sourcePackId, String targetScope, CurrentUser user) {
        jdbc.sql("SELECT id FROM wiki_pack WHERE id = ? FOR UPDATE")
                .param(sourcePackId).query(Long.class).optional()
                .orElseThrow(() -> new BusinessException("来源知识包不存在"));
        WikiPackRecord source = getPack(sourcePackId);
        boolean validTransition = ("PROJECT".equals(source.scope()) && "REUSABLE".equals(targetScope))
                || ("REUSABLE".equals(source.scope()) && "SYSTEM".equals(targetScope));
        if (!validTransition) throw new BusinessException("知识包只能按项目级→跨项目复用级→系统级逐级提交");
        if (!"ACTIVE".equals(source.status()) || !"APPROVED".equals(source.reviewStatus())) {
            throw new BusinessException("来源知识包须先审核并激活");
        }
        Integer approvedCount = jdbc.sql("""
                SELECT COUNT(*) FROM wiki_entry WHERE pack_id = ?
                  AND review_status = 'APPROVED' AND effective_status = 'ACTIVE'
                """).param(sourcePackId).query(Integer.class).single();
        if (approvedCount == null || approvedCount == 0) {
            throw new BusinessException("来源知识包没有已审核生效的条目");
        }
        var existing = jdbc.sql("""
                SELECT * FROM wiki_pack WHERE promotion_source_pack_id = :sourcePackId
                  AND scope = :targetScope AND review_status <> 'REJECTED'
                ORDER BY id DESC LIMIT 1
                """).param("sourcePackId", sourcePackId).param("targetScope", targetScope)
                .query(this::mapPack).optional();
        if (existing.isPresent()) return existing.get();
        Integer previous = jdbc.sql("""
                SELECT COUNT(*) FROM wiki_pack WHERE promotion_source_pack_id = :sourcePackId
                  AND scope = :targetScope
                """).param("sourcePackId", sourcePackId).param("targetScope", targetScope)
                .query(Integer.class).single();
        int revision = (previous == null ? 0 : previous) + 1;
        String suffix = "（" + ("REUSABLE".equals(targetScope) ? "复用" : "系统")
                + "候选 " + sourcePackId + "-" + revision + "）";
        String name = source.name().substring(0, Math.min(source.name().length(), 255 - suffix.length())) + suffix;
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO wiki_pack(project_id, scope, name, status, review_status, trust_level,
                    source_type, promotion_source_pack_id, description, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'DRAFT', 'PENDING', ?, 'WIKI_PROMOTION', ?, ?, ?, ?, ?)
                """, source.projectId(), targetScope, name, source.trustLevel(), sourcePackId,
                source.description(), user.id(), now, now);
        Long candidateId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcTemplate.update("""
                INSERT INTO wiki_entry(pack_id, entry_type, title, content, keywords_json, source_refs_json,
                    source_type, source_ref_type, source_ref_id, promotion_source_entry_id,
                    review_status, confidence, effective_status, created_by,
                    created_at, updated_at)
                SELECT ?, entry_type, title, content, keywords_json, source_refs_json,
                       source_type, source_ref_type, source_ref_id, id,
                       'PENDING', confidence, 'INACTIVE', ?, ?, ?
                FROM wiki_entry WHERE pack_id = ? AND review_status = 'APPROVED'
                  AND effective_status = 'ACTIVE'
                """, candidateId, user.id(), now, now, sourcePackId);
        jdbcTemplate.update("""
                INSERT INTO wiki_entry_relation(entry_id, related_tom_id, relation_type)
                SELECT promoted.id, link.related_tom_id, link.relation_type
                FROM wiki_entry promoted
                JOIN wiki_entry source ON source.id = promoted.promotion_source_entry_id
                JOIN wiki_entry_relation link ON link.entry_id = source.id
                WHERE promoted.pack_id = ? AND link.related_tom_id IS NOT NULL
                """, candidateId);
        return getPack(candidateId);
    }

    /** Only reviewed, active knowledge is available to an agent. Counts are independent of the sample limit. */
    public Map<String, Object> activeContext(Long projectId, String query, int limit) {
        String visibility = """
                FROM wiki_entry we JOIN wiki_pack wp ON wp.id = we.pack_id
                WHERE wp.status = 'ACTIVE' AND wp.review_status = 'APPROVED'
                  AND we.review_status = 'APPROVED' AND we.effective_status = 'ACTIVE'
                  AND (wp.project_id = :projectId OR wp.scope IN ('REUSABLE', 'SYSTEM'))
                """;
        var counts = jdbc.sql("SELECT wp.scope, COUNT(*) AS entry_count " + visibility + " GROUP BY wp.scope")
                .param("projectId", projectId)
                .query((rs, row) -> Map.entry(rs.getString("scope"), rs.getLong("entry_count"))).list();
        Map<String, Long> availableByScope = new LinkedHashMap<>();
        availableByScope.put("PROJECT", 0L);
        availableByScope.put("REUSABLE", 0L);
        availableByScope.put("SYSTEM", 0L);
        for (var count : counts) availableByScope.put(count.getKey(), count.getValue());

        String term = query == null ? "" : query.strip();
        if (term.length() > 100) throw new BusinessException("Wiki 检索词最多 100 字");
        String match = term.isEmpty() ? "" : " AND (we.title LIKE :pattern ESCAPE '!' OR we.content LIKE :pattern ESCAPE '!' OR we.keywords_json LIKE :pattern ESCAPE '!')";
        var countQuery = jdbc.sql("SELECT COUNT(*) " + visibility + match).param("projectId", projectId);
        if (!term.isEmpty()) countQuery = countQuery.param("pattern", likePattern(term));
        long matchedCount = countQuery.query(Long.class).single();

        var sampleQuery = jdbc.sql("""
                SELECT we.id, we.entry_type, we.title, we.content, we.source_refs_json,
                       we.source_type, we.source_ref_type, we.source_ref_id,
                       wp.id AS pack_id, wp.name AS pack_name, wp.scope
                """ + visibility + match + """
                ORDER BY CASE WHEN we.entry_type = 'HISTORY' OR we.source_type IN
                                  ('IMPORTED_HISTORICAL_CASE', 'FORMAL_TEST_CASE') THEN 1 ELSE 0 END,
                         CASE wp.scope WHEN 'PROJECT' THEN 1 WHEN 'REUSABLE' THEN 2 ELSE 3 END,
                         we.confidence DESC, we.id DESC LIMIT :limit
                """).param("projectId", projectId).param("limit", Math.max(1, Math.min(limit, 20)));
        if (!term.isEmpty()) sampleQuery = sampleQuery.param("pattern", likePattern(term));
        var entries = sampleQuery.query((rs, row) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entryId", rs.getLong("id"));
            entry.put("packId", rs.getLong("pack_id"));
            entry.put("packName", rs.getString("pack_name"));
            entry.put("scope", rs.getString("scope"));
            entry.put("entryType", rs.getString("entry_type"));
            entry.put("sourceType", rs.getString("source_type"));
            entry.put("sourceRefType", rs.getString("source_ref_type"));
            entry.put("sourceRefId", rs.getObject("source_ref_id"));
            entry.put("evidenceRole", evidenceRole(rs.getString("entry_type"), rs.getString("source_type")));
            entry.put("title", rs.getString("title"));
            String content = rs.getString("content");
            entry.put("contentSnippet", content == null || content.length() <= 500 ? content : content.substring(0, 500) + "…");
            entry.put("sourceRefsJson", rs.getString("source_refs_json"));
            return entry;
        }).list();
        return Map.of("availableByScope", availableByScope, "matchedCount", matchedCount,
                "entries", entries, "truncated", matchedCount > entries.size());
    }

    /** Full text for a result already visible in the reviewed, active Wiki inventory. */
    public Map<String, Object> activeEntry(Long projectId, Long entryId) {
        if (entryId == null || entryId < 1) throw new BusinessException("Wiki 条目编号无效");
        var entries = jdbc.sql("""
                SELECT we.id, we.entry_type, we.title, we.content, we.source_refs_json,
                       we.source_type, we.source_ref_type, we.source_ref_id,
                       wp.id AS pack_id, wp.name AS pack_name, wp.scope
                FROM wiki_entry we JOIN wiki_pack wp ON wp.id = we.pack_id
                WHERE we.id = :entryId AND wp.status = 'ACTIVE' AND wp.review_status = 'APPROVED'
                  AND we.review_status = 'APPROVED' AND we.effective_status = 'ACTIVE'
                  AND (wp.project_id = :projectId OR wp.scope IN ('REUSABLE', 'SYSTEM'))
                """).param("entryId", entryId).param("projectId", projectId)
                .query((rs, row) -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("entryId", rs.getLong("id"));
                    entry.put("packId", rs.getLong("pack_id"));
                    entry.put("packName", rs.getString("pack_name"));
                    entry.put("scope", rs.getString("scope"));
                    entry.put("entryType", rs.getString("entry_type"));
                    entry.put("sourceType", rs.getString("source_type"));
                    entry.put("sourceRefType", rs.getString("source_ref_type"));
                    entry.put("sourceRefId", rs.getObject("source_ref_id"));
                    entry.put("evidenceRole", evidenceRole(rs.getString("entry_type"), rs.getString("source_type")));
                    entry.put("title", rs.getString("title"));
                    entry.put("content", rs.getString("content"));
                    entry.put("sourceRefsJson", rs.getString("source_refs_json"));
                    return entry;
                }).list();
        if (entries.isEmpty()) throw new BusinessException("Wiki 条目不存在或当前不可见");
        return entries.get(0);
    }

    public List<Long> listTomLinks(Long entryId) {
        getEntry(entryId);
        return jdbc.sql("SELECT related_tom_id FROM wiki_entry_relation WHERE entry_id = ? AND related_tom_id IS NOT NULL ORDER BY related_tom_id")
                .param(entryId).query(Long.class).list();
    }

    public static String evidenceRole(String entryType, String sourceType) {
        return "HISTORY".equals(entryType) || "IMPORTED_HISTORICAL_CASE".equals(sourceType)
                || "FORMAL_TEST_CASE".equals(sourceType)
                ? "HISTORICAL_COVERAGE_ONLY" : "SEMANTIC_KNOWLEDGE";
    }

    /** Changing the meaning of an approved entry requires human review again. */
    @Transactional
    public List<Long> replaceTomLinks(Long entryId, List<Long> tomIds, CurrentUser user) {
        WikiPackRecord pack = getPackForEntry(entryId);
        List<Long> ids = tomIds == null ? List.of() : tomIds.stream().distinct().toList();
        if (ids.size() > 30 || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new BusinessException("最多关联 30 个有效 TOM 对象");
        }
        for (Long id : ids) {
            var visible = jdbc.sql("""
                    SELECT id FROM test_object_model WHERE id = ? AND status = 'ACTIVE'
                      AND (project_id = ? OR scope = 'SYSTEM')
                    """).param(id).param(pack.projectId()).query(Long.class).list();
            if (visible.isEmpty()) throw new BusinessException("TOM 对象不存在或当前不可关联：" + id);
        }
        List<Long> existing = listTomLinks(entryId);
        if (existing.equals(ids.stream().sorted().toList())) return existing;
        jdbcTemplate.update("DELETE FROM wiki_entry_relation WHERE entry_id = ? AND related_tom_id IS NOT NULL", entryId);
        for (Long id : ids) {
            jdbcTemplate.update("INSERT INTO wiki_entry_relation(entry_id, related_tom_id, relation_type) VALUES (?, ?, 'EXPLAINS')",
                    entryId, id);
        }
        jdbcTemplate.update("""
                UPDATE wiki_entry SET review_status = 'PENDING', effective_status = 'INACTIVE',
                    approved_by = NULL, approved_at = NULL, updated_at = ? WHERE id = ?
                """, timeProvider.now(), entryId);
        return ids;
    }

    private String likePattern(String term) {
        return "%" + term.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    public List<WikiPackRecord> listPacksForAdmin(Long projectId, String scope, String status,
                                                   String reviewStatus) {
        StringBuilder sql = new StringBuilder("SELECT * FROM wiki_pack WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (projectId != null) {
            sql.append(" AND project_id = ?");
            params.add(projectId);
        }
        if (scope != null && !scope.isBlank()) {
            validateScope(scope);
            sql.append(" AND scope = ?");
            params.add(scope);
        }
        if (status != null && !status.isBlank()) {
            validatePackStatus(status);
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            validateReviewStatus(reviewStatus);
            sql.append(" AND review_status = ?");
            params.add(reviewStatus);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC");
        return jdbcTemplate.query(sql.toString(), this::mapPack, params.toArray());
    }

    public WikiPackRecord getPack(Long packId) {
        var results = jdbc.sql("SELECT * FROM wiki_pack WHERE id = ?")
                .param(packId)
                .query(this::mapPack).list();
        if (results.isEmpty()) throw new BusinessException("Wiki 包不存在");
        return results.get(0);
    }

    /**
     * Editing is deliberately a governed operation: the changed pack must be reviewed again
     * before it can participate in semantic retrieval.
     */
    @Transactional
    public WikiPackRecord updatePackMetadata(Long packId, String name, String description, CurrentUser user) {
        WikiPackRecord pack = getPack(packId);
        if (name == null || name.isBlank()) {
            throw new BusinessException("知识包名称不能为空");
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE wiki_pack
                SET name = ?, description = ?, review_status = 'PENDING', status = 'DRAFT',
                    reviewed_by = NULL, reviewed_at = NULL, updated_at = ?
                WHERE id = ?
                """, name.trim(), description == null ? "" : description.trim(), now, packId);
        return getPack(packId);
    }

    public Long projectIdForPack(Long packId) {
        return getPack(packId).projectId();
    }

    public WikiPackRecord getPackForEntry(Long entryId) {
        var results = jdbc.sql("""
                SELECT wp.* FROM wiki_pack wp
                JOIN wiki_entry we ON we.pack_id = wp.id
                WHERE we.id = ?
                """)
                .param(entryId)
                .query(this::mapPack)
                .list();
        if (results.isEmpty()) throw new BusinessException("Wiki 条目不存在");
        return results.get(0);
    }

    @Transactional
    public WikiPackRecord createPack(Long projectId, String scope, String name, String description, CurrentUser user) {
        validateScope(scope);
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO wiki_pack(project_id, scope, name, status, review_status, description, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'DRAFT', 'PENDING', ?, ?, ?, ?)
                """, projectId, scope, name, description, user.id(), now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getPack(id);
    }

    @Transactional
    public WikiPackRecord updatePackStatus(Long packId, String status, CurrentUser user) {
        validatePackStatus(status);
        WikiPackRecord pack = getPack(packId);
        if ("ACTIVE".equals(status)) {
            if (!"APPROVED".equals(pack.reviewStatus())) {
                throw new BusinessException("知识包审核通过后才能激活");
            }
            Integer approvedEntries = jdbc.sql("""
                    SELECT COUNT(*) FROM wiki_entry
                    WHERE pack_id = ? AND review_status = 'APPROVED' AND effective_status = 'ACTIVE'
                    """).param(packId).query(Integer.class).single();
            if (approvedEntries == null || approvedEntries == 0) {
                throw new BusinessException("知识包至少需要一个已审核通过的生效条目");
            }
        }
        LocalDateTime now = timeProvider.now();
        int updated = jdbcTemplate.update("UPDATE wiki_pack SET status = ?, updated_at = ? WHERE id = ?",
                status, now, packId);
        if (updated == 0) {
            throw new BusinessException("Wiki 包不存在");
        }
        return getPack(packId);
    }

    @Transactional
    public WikiPackRecord reviewPack(Long packId, String reviewStatus, CurrentUser user) {
        validateReviewStatus(reviewStatus);
        LocalDateTime now = timeProvider.now();
        String nextStatus = "APPROVED".equals(reviewStatus) ? "DRAFT" : "INACTIVE";
        int updated = jdbcTemplate.update("""
                UPDATE wiki_pack SET review_status = ?, status = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ?
                WHERE id = ? AND review_status = 'PENDING'
                """, reviewStatus, nextStatus, user.id(), now, now, packId);
        if (updated == 0) throw new BusinessException("Wiki 包不存在或已审核，请刷新后重试");
        if ("REJECTED".equals(reviewStatus)) {
            jdbcTemplate.update("UPDATE wiki_entry SET effective_status = 'INACTIVE', updated_at = ? WHERE pack_id = ?",
                    now, packId);
        }
        return getPack(packId);
    }

    @Transactional
    public void deletePack(Long packId) {
        jdbcTemplate.update("DELETE link FROM wiki_entry_relation link JOIN wiki_entry entry ON entry.id = link.entry_id WHERE entry.pack_id = ?", packId);
        jdbcTemplate.update("DELETE FROM wiki_entry WHERE pack_id = ?", packId);
        jdbcTemplate.update("DELETE FROM wiki_pack WHERE id = ?", packId);
    }

    // ---- Wiki Entry CRUD ----

    public List<WikiEntryRecord> listEntries(Long packId) {
        return jdbc.sql("SELECT * FROM wiki_entry WHERE pack_id = ? ORDER BY created_at DESC")
                .param(packId)
                .query(this::mapEntry).list();
    }

    public WikiEntryRecord getEntry(Long entryId) {
        var results = jdbc.sql("SELECT * FROM wiki_entry WHERE id = ?")
                .param(entryId)
                .query(this::mapEntry).list();
        if (results.isEmpty()) throw new BusinessException("Wiki 条目不存在");
        return results.get(0);
    }

    /**
     * A content change invalidates the previous human review. Source references stay immutable
     * so the reviewer can still trace the candidate back to its origin.
     */
    @Transactional
    public WikiEntryRecord updateEntry(Long entryId, String entryType, String title, String content, CurrentUser user) {
        if (entryType == null || entryType.isBlank() || title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new BusinessException("条目类型、标题和内容不能为空");
        }
        LocalDateTime now = timeProvider.now();
        int updated = jdbcTemplate.update("""
                UPDATE wiki_entry
                SET entry_type = ?, title = ?, content = ?, review_status = 'PENDING',
                    effective_status = 'INACTIVE', approved_by = NULL, approved_at = NULL,
                    rejected_by = NULL, rejected_at = NULL, rejected_reason = NULL, updated_at = ?
                WHERE id = ?
                """, entryType.trim(), title.trim(), content.trim(), now, entryId);
        if (updated == 0) throw new BusinessException("Wiki 条目不存在");
        return getEntry(entryId);
    }

    @Transactional
    public WikiEntryRecord createEntry(Long packId, String entryType, String title, String content,
                                        String keywordsJson, String sourceRefsJson, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO wiki_entry(pack_id, entry_type, title, content, keywords_json, source_refs_json,
                    review_status, confidence, effective_status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0.5, 'INACTIVE', ?, ?, ?)
                """, packId, entryType, title, content, keywordsJson, sourceRefsJson, user.id(), now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getEntry(id);
    }

    @Transactional
    public WikiEntryRecord reviewEntry(Long entryId, String reviewStatus, CurrentUser user) {
        validateReviewStatus(reviewStatus);
        LocalDateTime now = timeProvider.now();
        String effectiveStatus = "APPROVED".equals(reviewStatus) ? "ACTIVE" : "INACTIVE";
        int updated = jdbcTemplate.update("""
                UPDATE wiki_entry SET review_status = ?, effective_status = ?,
                    approved_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_by END,
                    approved_at = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_at END,
                    rejected_by = CASE WHEN ? = 'REJECTED' THEN ? ELSE rejected_by END,
                    rejected_at = CASE WHEN ? = 'REJECTED' THEN ? ELSE rejected_at END,
                    updated_at = ? WHERE id = ? AND review_status = 'PENDING'
                """, reviewStatus, effectiveStatus,
                reviewStatus, user.id(), reviewStatus, now,
                reviewStatus, user.id(), reviewStatus, now, now, entryId);
        if (updated == 0) {
            throw new BusinessException("Wiki 条目不存在或已审核，请刷新后重试");
        }
        if ("APPROVED".equals(reviewStatus)) {
            jdbcTemplate.update("""
                    UPDATE wiki_entry previous
                    JOIN wiki_entry current
                      ON current.id = ?
                     AND current.pack_id = previous.pack_id
                     AND current.candidate_key IS NOT NULL
                     AND current.candidate_key = previous.candidate_key
                    SET previous.effective_status = 'INACTIVE', previous.updated_at = ?
                    WHERE previous.id <> current.id AND previous.effective_status = 'ACTIVE'
                    """, entryId, now);
        }
        return getEntry(entryId);
    }

    private void validateScope(String scope) {
        if (!Set.of("PROJECT", "REUSABLE", "SYSTEM").contains(scope)) {
            throw new BusinessException("Wiki 范围不支持：" + scope);
        }
    }

    private void validatePackStatus(String status) {
        if (!Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED").contains(status)) {
            throw new BusinessException("Wiki 包状态不支持：" + status);
        }
    }

    private void validateReviewStatus(String reviewStatus) {
        if (!Set.of("PENDING", "APPROVED", "REJECTED").contains(reviewStatus)) {
            throw new BusinessException("Wiki 审核状态不支持：" + reviewStatus);
        }
    }

    // ---- Mapper ----

    private WikiPackRecord mapPack(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WikiPackRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("scope"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("review_status"),
                rs.getString("trust_level"),
                rs.getString("source_type"),
                rs.getObject("promotion_source_pack_id") == null ? null : rs.getLong("promotion_source_pack_id"),
                rs.getString("description"),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private WikiEntryRecord mapEntry(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WikiEntryRecord(
                rs.getLong("id"),
                rs.getLong("pack_id"),
                rs.getString("entry_type"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("keywords_json"),
                rs.getString("source_refs_json"),
                rs.getString("review_status"),
                rs.getBigDecimal("confidence"),
                rs.getString("effective_status"),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
