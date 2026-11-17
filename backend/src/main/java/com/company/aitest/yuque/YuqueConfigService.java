package com.company.aitest.yuque;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class YuqueConfigService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public YuqueConfigService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public List<YuqueConfigRecord> list(Long projectId) {
        return jdbc.sql("select * from yuque_config where project_id = :projectId order by id desc")
                .param("projectId", projectId)
                .query(this::map)
                .list();
    }

    public YuqueConfigRecord create(Long projectId, CreateYuqueConfigCommand command, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into yuque_config(project_id, config_name, host, auth_mode, username,
                                         save_credential, repo_scope, exporter_type, enabled,
                                         created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                projectId,
                command.configName(),
                command.host(),
                command.authMode(),
                command.username(),
                command.saveCredential(),
                command.repoScope(),
                command.exporterType(),
                command.enabled(),
                user.id(),
                now,
                now
        );
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return jdbc.sql("select * from yuque_config where id = :id").param("id", id).query(this::map).single();
    }

    private YuqueConfigRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new YuqueConfigRecord(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("config_name"),
                rs.getString("host"),
                rs.getString("auth_mode"),
                rs.getString("username"),
                rs.getInt("save_credential"),
                rs.getString("repo_scope"),
                rs.getString("exporter_type"),
                rs.getInt("enabled"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    public record CreateYuqueConfigCommand(String configName, String host, String authMode, String username,
                                           Integer saveCredential, String repoScope, String exporterType,
                                           Integer enabled) {
    }
}
