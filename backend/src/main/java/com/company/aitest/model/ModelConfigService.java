package com.company.aitest.model;

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
public class ModelConfigService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public ModelConfigService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public List<ModelConfigRecord> list() {
        return jdbc.sql("select * from model_config order by id desc").query(this::map).list();
    }

    public List<ModelConfigRecord> listEnabled() {
        return jdbc.sql("""
                select * from model_config
                where status = 'ACTIVE'
                order by id desc
                """).query(this::map).list();
    }

    public ModelConfigRecord create(CreateModelConfigCommand command, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into model_config(config_name, provider, model_name, endpoint, api_key_encrypted, status, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, command.configName(), command.provider(), command.modelName(), command.endpoint(), command.apiKey(), user.id(), now, now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return jdbc.sql("select * from model_config where id = :id").param("id", id).query(this::map).single();
    }

    public ModelConfigRecord update(Long id, UpdateModelConfigCommand command) {
        LocalDateTime now = timeProvider.now();
        if (command.apiKey() != null && !command.apiKey().isBlank()) {
            jdbcTemplate.update("""
                    update model_config set config_name=?, provider=?, model_name=?, endpoint=?, api_key_encrypted=?, updated_at=? where id=?
                    """, command.configName(), command.provider(), command.modelName(), command.endpoint(), command.apiKey(), now, id);
        } else {
            jdbcTemplate.update("""
                    update model_config set config_name=?, provider=?, model_name=?, endpoint=?, updated_at=? where id=?
                    """, command.configName(), command.provider(), command.modelName(), command.endpoint(), now, id);
        }
        return jdbc.sql("select * from model_config where id = :id").param("id", id).query(this::map).single();
    }

    public void delete(Long id) {
        jdbcTemplate.update("delete from model_config where id = ?", id);
    }

    public RuntimeModelConfig getRuntimeConfig(Long modelConfigId) {
        return jdbc.sql("""
                select id, provider, model_name, endpoint, api_key_encrypted, status
                from model_config
                where id = :id
                """)
                .param("id", modelConfigId)
                .query((rs, rowNum) -> new RuntimeModelConfig(
                        rs.getLong("id"),
                        rs.getString("provider"),
                        rs.getString("model_name"),
                        rs.getString("endpoint"),
                        rs.getString("api_key_encrypted"),
                        rs.getString("status")
                ))
                .single();
    }

    private ModelConfigRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ModelConfigRecord(rs.getLong("id"), rs.getString("config_name"), rs.getString("provider"),
                rs.getString("model_name"), rs.getString("endpoint"), rs.getString("status"),
                rs.getLong("created_by"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    public record CreateModelConfigCommand(String configName, String provider, String modelName, String endpoint, String apiKey) {
    }

    public record UpdateModelConfigCommand(String configName, String provider, String modelName, String endpoint, String apiKey) {
    }

    public record RuntimeModelConfig(Long id, String provider, String modelName, String endpoint, String apiKey,
                                     String status) {
    }
}
