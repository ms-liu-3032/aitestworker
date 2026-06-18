package com.company.aitest.prompt;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prompt 模板管理。
 * <p>
 * Sprint 4 起：
 * - 修改 PUBLIC/SYSTEM 提示词不是覆盖，而是新建一行（version+1），旧版状态 DEPRECATED
 * - 普通用户可对公共提示词做 fork → PERSONAL 副本
 * - 普通用户可提议公共：PERSONAL → 候选 PUBLIC (review_status=PENDING)，管理员审核后 ACTIVE+APPROVED
 * - listEnabled() 仅返回 ACTIVE + 非 DEPRECATED + review_status APPROVED 的模板
 */
@Service
public class PromptTemplateService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final HashService hashService;

    public PromptTemplateService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider, HashService hashService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.hashService = hashService;
    }

    public List<PromptTemplateRecord> list() {
        return jdbc.sql("select * from prompt_template order by id desc").query(this::map).list();
    }

    public List<PromptTemplateRecord> listEnabled() {
        return jdbc.sql("""
                select * from prompt_template
                where status = 'ACTIVE'
                  and review_status = 'APPROVED'
                  and deprecated_at is null
                order by id desc
                """).query(this::map).list();
    }

    public PromptTemplateRecord create(CreatePromptCommand command, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String hash = hashService.sha256(command.content());
        jdbcTemplate.update("""
                insert into prompt_template(prompt_name, prompt_type, scope, content, content_hash, version,
                  status, review_status, contributor_user_id, contributor_username, created_by, created_at, updated_at)
                values (?, ?, 'PUBLIC', ?, ?, 1, 'ACTIVE', 'APPROVED', ?, ?, ?, ?, ?)
                """, command.promptName(), command.promptType(), command.content(), hash,
                user.id(), user.username(), user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getById(id);
    }

    /**
     * 修改公共提示词：旧版标记 DEPRECATED，插入新版本（version+1），不破坏历史 task 的 prompt_snapshot。
     */
    @Transactional
    public PromptTemplateRecord updateAsNewVersion(Long oldId, String newContent, CurrentUser user) {
        PromptTemplateRecord old = getById(oldId);
        if (old == null) {
            throw new BusinessException("提示词不存在");
        }
        if (!"PUBLIC".equals(old.scope()) && !"SYSTEM".equals(old.scope())) {
            throw new BusinessException("仅公共/系统提示词使用版本制；个人提示词请直接 update content");
        }
        LocalDateTime now = timeProvider.now();
        String hash = hashService.sha256(newContent);
        int currentVersion = old.version() == null ? 1 : old.version();
        // 1. 旧版 DEPRECATED
        jdbcTemplate.update("""
                update prompt_template
                set status = 'DEPRECATED', deprecated_at = ?, deprecated_by = ?, deprecated_reason = ?, updated_at = ?
                where id = ?
                """, now, user.id(), "superseded by new version", now, oldId);
        // 2. 写新版（继承 SYSTEM/PUBLIC 等 scope，version+1）
        jdbcTemplate.update("""
                insert into prompt_template(prompt_name, prompt_type, scope, content, content_hash, version,
                  status, review_status, contributor_user_id, contributor_username, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', 'APPROVED', ?, ?, ?, ?, ?)
                """, old.promptName(), old.promptType(), old.scope(), newContent, hash, currentVersion + 1,
                user.id(), user.username(), user.id(), now, now);
        Long newId = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getById(newId);
    }

    /**
     * 普通用户把公共提示词 fork 成自己的 PERSONAL 副本，可以随便改而不影响公共版本。
     */
    @Transactional
    public PromptTemplateRecord forkToPersonal(Long publicId, CurrentUser user) {
        PromptTemplateRecord src = getById(publicId);
        if (src == null) {
            throw new BusinessException("提示词不存在");
        }
        if (!"PUBLIC".equals(src.scope()) && !"SYSTEM".equals(src.scope())) {
            throw new BusinessException("只能 fork PUBLIC/SYSTEM 范围的提示词");
        }
        LocalDateTime now = timeProvider.now();
        String hash = hashService.sha256(src.content());
        jdbcTemplate.update("""
                insert into prompt_template(prompt_name, prompt_type, scope, content, content_hash, version,
                  status, review_status, contributor_user_id, contributor_username, created_by, created_at, updated_at)
                values (?, ?, 'PERSONAL', ?, ?, 1, 'ACTIVE', 'APPROVED', ?, ?, ?, ?, ?)
                """, src.promptName() + " (我的副本)", src.promptType(), src.content(), hash,
                user.id(), user.username(), user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getById(id);
    }

    /**
     * 普通用户把自己的 PERSONAL 提示词提议为候选 PUBLIC，等管理员审核。
     */
    @Transactional
    public PromptTemplateRecord proposeAsPublic(Long personalId, CurrentUser user) {
        PromptTemplateRecord src = getById(personalId);
        if (src == null) {
            throw new BusinessException("提示词不存在");
        }
        if (!"PERSONAL".equals(src.scope())) {
            throw new BusinessException("仅 PERSONAL 提示词可提议公共");
        }
        if (!user.id().equals(src.contributorUserId())) {
            throw new BusinessException("仅原创建人可提议公共");
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into prompt_template(prompt_name, prompt_type, scope, content, content_hash, version,
                  status, review_status, contributor_user_id, contributor_username, created_by, created_at, updated_at)
                values (?, ?, 'PUBLIC', ?, ?, 1, 'REVIEWING', 'PENDING', ?, ?, ?, ?, ?)
                """, src.promptName(), src.promptType(), src.content(), src.content(),
                user.id(), user.username(), user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return getById(id);
    }

    /**
     * 管理员审核：通过则置 ACTIVE+APPROVED，拒绝则 DEPRECATED。
     */
    @Transactional
    public PromptTemplateRecord review(Long promptId, boolean approved, String reason, CurrentUser admin) {
        PromptTemplateRecord src = getById(promptId);
        if (src == null) {
            throw new BusinessException("提示词不存在");
        }
        LocalDateTime now = timeProvider.now();
        if (approved) {
            jdbcTemplate.update("""
                    update prompt_template
                    set status = 'ACTIVE', review_status = 'APPROVED', updated_at = ?
                    where id = ?
                    """, now, promptId);
        } else {
            jdbcTemplate.update("""
                    update prompt_template
                    set status = 'DEPRECATED', review_status = 'REJECTED',
                        deprecated_at = ?, deprecated_by = ?, deprecated_reason = ?, updated_at = ?
                    where id = ?
                    """, now, admin.id(), reason, now, promptId);
        }
        return getById(promptId);
    }

    public PromptTemplateRecord getById(Long id) {
        return jdbc.sql("select * from prompt_template where id = :id").param("id", id).query(this::map).optional().orElse(null);
    }

    private PromptTemplateRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new PromptTemplateRecord(
                rs.getLong("id"),
                rs.getString("prompt_name"),
                rs.getString("prompt_type"),
                rs.getString("scope"),
                rs.getString("content"),
                rs.getString("status"),
                getNullableInt(rs, "version"),
                rs.getString("review_status"),
                rs.getLong("contributor_user_id"),
                rs.getString("contributor_username"),
                rs.getTimestamp("deprecated_at") == null ? null : rs.getTimestamp("deprecated_at").toLocalDateTime(),
                getNullableLong(rs, "deprecated_by"),
                rs.getString("deprecated_reason"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long getNullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public record CreatePromptCommand(String promptName, String promptType, String content) {
    }
}
