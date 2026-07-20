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
    private final FileStorageService fileStorageService;

    public FileResourceService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                               FileStorageService fileStorageService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.fileStorageService = fileStorageService;
    }

    public FileResourceRecord register(Long projectId, RegisterFileCommand command, CurrentUser user) {
        fileStorageService.validateStoragePath(command.storagePath());
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

    public FileResourceRecord getForProject(Long projectId, Long fileId) {
        return jdbc.sql("select * from file_resource where id = :id and project_id = :projectId")
                .param("id", fileId)
                .param("projectId", projectId)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new com.company.aitest.common.BusinessException("文件不存在"));
    }

    private FileResourceRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new FileResourceRecord(rs.getLong("id"), rs.getLong("project_id"), rs.getString("file_name"),
                rs.getString("file_type"), rs.getString("storage_type"), rs.getString("storage_path"),
                rs.getString("content_hash"), rs.getLong("created_by"), rs.getTimestamp("created_at").toLocalDateTime());
    }

    public record RegisterFileCommand(String fileName, String fileType, String storageType, String storagePath, String contentHash) {
    }
}
