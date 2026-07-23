package com.company.aitest.wiki;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
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
        return jdbc.sql("SELECT * FROM wiki_pack WHERE project_id = ? ORDER BY created_at DESC")
                .param(projectId)
                .query(this::mapPack).list();
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
                WHERE id = ?
                """, reviewStatus, nextStatus, user.id(), now, now, packId);
        if (updated == 0) throw new BusinessException("Wiki 包不存在");
        if ("REJECTED".equals(reviewStatus)) {
            jdbcTemplate.update("UPDATE wiki_entry SET effective_status = 'INACTIVE', updated_at = ? WHERE pack_id = ?",
                    now, packId);
        }
        return getPack(packId);
    }

    @Transactional
    public void deletePack(Long packId) {
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
                    updated_at = ? WHERE id = ?
                """, reviewStatus, effectiveStatus,
                reviewStatus, user.id(), reviewStatus, now,
                reviewStatus, user.id(), reviewStatus, now, now, entryId);
        if (updated == 0) {
            throw new BusinessException("Wiki 条目不存在");
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
