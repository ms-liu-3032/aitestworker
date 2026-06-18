package com.company.aitest.file;

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
public class FileResourceService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public FileResourceService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public FileResourceRecord register(Long projectId, RegisterFileCommand command, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                insert into file_resource(project_id, file_name, file_type, storage_type, storage_path, content_hash, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, projectId, command.fileName(), command.fileType(), command.storageType(), command.storagePath(),
                command.contentHash(), user.id(), now);
        Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
        return jdbc.sql("select * from file_resource where id = :id").param("id", id).query(this::map).single();
    }

    public List<FileResourceRecord> list(Long projectId) {
        return jdbc.sql("select * from file_resource where project_id = :projectId order by id desc")
                .param("projectId", projectId).query(this::map).list();
    }

    private FileResourceRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new FileResourceRecord(rs.getLong("id"), rs.getLong("project_id"), rs.getString("file_name"),
                rs.getString("file_type"), rs.getString("storage_type"), rs.getString("storage_path"),
                rs.getString("content_hash"), rs.getLong("created_by"), rs.getTimestamp("created_at").toLocalDateTime());
    }

    public record RegisterFileCommand(String fileName, String fileType, String storageType, String storagePath, String contentHash) {
    }
}
