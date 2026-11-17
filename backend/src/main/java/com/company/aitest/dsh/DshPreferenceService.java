package com.company.aitest.dsh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshPreferenceService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ProjectAccessService access;

    public DshPreferenceService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                ProjectAccessService access) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.access = access;
    }

    public Preference get(Long projectId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        String value = jdbc.sql("SELECT style_text FROM dsh_user_preference WHERE user_id = :userId AND project_id = :projectId")
                .param("userId", user.id()).param("projectId", projectId)
                .query(String.class).optional().orElse("");
        return new Preference(projectId, value);
    }

    @Transactional
    public Preference save(Long projectId, String styleText, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (styleText == null || styleText.length() > 4000) throw new BusinessException("个人写作偏好最多 4000 字");
        jdbcTemplate.update("""
                INSERT INTO dsh_user_preference(user_id, project_id, style_text, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE style_text = VALUES(style_text), updated_at = VALUES(updated_at)
                """, user.id(), projectId, styleText.trim(), timeProvider.now());
        return new Preference(projectId, styleText.trim());
    }

    @Transactional
    public Candidate propose(Long projectId, String styleText, String reason, Long sourceDraftId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (styleText == null || styleText.isBlank() || styleText.length() > 4000
                || reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new BusinessException("偏好候选和依据不能为空，分别最多 4000/1000 字");
        }
        if (sourceDraftId != null) {
            if (sourceDraftId < 1) throw new BusinessException("来源草稿 ID 无效");
            boolean owned = jdbc.sql("""
                    SELECT id FROM test_case_draft
                    WHERE id = :draftId AND project_id = :projectId AND created_by = :userId
                    """).param("draftId", sourceDraftId).param("projectId", projectId).param("userId", user.id())
                    .query(Long.class).optional().isPresent();
            if (!owned) throw new BusinessException("来源草稿不属于当前用户和项目");
        }
        String normalized = styleText.trim();
        String hash = sha256(normalized);
        var now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO dsh_preference_candidate(user_id, project_id, proposed_style_text, reason,
                    source_draft_id, content_hash, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, user.id(), projectId, normalized, reason.trim(), sourceDraftId, hash, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return new Candidate(id, projectId, normalized, reason.trim(), sourceDraftId, hash, "PENDING");
    }

    public List<Candidate> candidates(Long projectId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        return jdbc.sql("""
                SELECT id, project_id, proposed_style_text, reason, source_draft_id, content_hash, status
                FROM dsh_preference_candidate
                WHERE user_id = :userId AND project_id = :projectId
                ORDER BY id DESC LIMIT 30
                """).param("userId", user.id()).param("projectId", projectId)
                .query((rs, rowNum) -> new Candidate(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("proposed_style_text"), rs.getString("reason"),
                        rs.getObject("source_draft_id", Long.class), rs.getString("content_hash"),
                        rs.getString("status"))).list();
    }

    @Transactional
    public Preference accept(Long projectId, Long candidateId, String reviewedHash, CurrentUser user) {
        Candidate candidate = lockCandidate(projectId, candidateId, user);
        if (!"PENDING".equals(candidate.status()) || reviewedHash == null
                || !candidate.contentHash().equals(reviewedHash)) {
            throw new BusinessException("候选内容已变化或已处理，请重新查看");
        }
        Preference saved = save(projectId, candidate.styleText(), user);
        jdbcTemplate.update("""
                UPDATE dsh_preference_candidate SET status = 'ACCEPTED', decided_at = ? WHERE id = ?
                """, timeProvider.now(), candidateId);
        return saved;
    }

    @Transactional
    public Candidate reject(Long projectId, Long candidateId, String reviewedHash, CurrentUser user) {
        Candidate candidate = lockCandidate(projectId, candidateId, user);
        if (!"PENDING".equals(candidate.status()) || reviewedHash == null
                || !candidate.contentHash().equals(reviewedHash)) {
            throw new BusinessException("候选内容已变化或已处理，请重新查看");
        }
        jdbcTemplate.update("""
                UPDATE dsh_preference_candidate SET status = 'REJECTED', decided_at = ? WHERE id = ?
                """, timeProvider.now(), candidateId);
        return new Candidate(candidate.id(), projectId, candidate.styleText(), candidate.reason(),
                candidate.sourceDraftId(), candidate.contentHash(), "REJECTED");
    }

    private Candidate lockCandidate(Long projectId, Long candidateId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (candidateId == null || candidateId < 1) throw new BusinessException("候选 ID 无效");
        return jdbc.sql("""
                SELECT id, project_id, proposed_style_text, reason, source_draft_id, content_hash, status
                FROM dsh_preference_candidate
                WHERE id = :id AND user_id = :userId AND project_id = :projectId FOR UPDATE
                """).param("id", candidateId).param("userId", user.id()).param("projectId", projectId)
                .query((rs, rowNum) -> new Candidate(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("proposed_style_text"), rs.getString("reason"),
                        rs.getObject("source_draft_id", Long.class), rs.getString("content_hash"),
                        rs.getString("status"))).optional()
                .orElseThrow(() -> new BusinessException("偏好候选不存在或无权访问"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Preference(Long projectId, String styleText) {}
    public record Candidate(Long id, Long projectId, String styleText, String reason, Long sourceDraftId,
                            String contentHash, String status) {}
}
