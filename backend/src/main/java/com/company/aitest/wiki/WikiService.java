package com.company.aitest.wiki;

import java.time.LocalDateTime;
import java.util.List;

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

    public WikiPackRecord getPack(Long packId) {
        var results = jdbc.sql("SELECT * FROM wiki_pack WHERE id = ?")
                .param(packId)
                .query(this::mapPack).list();
        if (results.isEmpty()) throw new BusinessException("Wiki 包不存在");
        return results.get(0);
    }

    @Transactional
    public WikiPackRecord createPack(Long projectId, String scope, String name, String description, CurrentUser user) {
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
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE wiki_pack SET status = ?, updated_at = ? WHERE id = ?",
                status, now, packId);
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
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0.5, 'ACTIVE', ?, ?, ?)
                """, packId, entryType, title, content, keywordsJson, sourceRefsJson, user.id(), now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getEntry(id);
    }

    @Transactional
    public WikiEntryRecord reviewEntry(Long entryId, String reviewStatus, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE wiki_entry SET review_status = ?, updated_at = ? WHERE id = ?",
                reviewStatus, now, entryId);
        return getEntry(entryId);
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
