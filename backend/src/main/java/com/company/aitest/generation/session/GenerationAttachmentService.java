package com.company.aitest.generation.session;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.file.FileStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class GenerationAttachmentService {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final FileStorageService fileStorageService;
    private final TimeProvider timeProvider;

    public GenerationAttachmentService(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                        FileStorageService fileStorageService, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.fileStorageService = fileStorageService;
        this.timeProvider = timeProvider;
    }

    public GenerationAttachmentRecord uploadAndRegister(Long sessionId, MultipartFile file, CurrentUser user) throws IOException {
        LocalDateTime now = timeProvider.now();
        String storagePath = fileStorageService.store(file, 0L); // projectId resolved from session later
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) ext = originalName.substring(dot + 1).toLowerCase();

        // Resolve projectId from session
        Long projectId = jdbc.sql("SELECT project_id FROM generation_session WHERE id = :sid")
                .param("sid", sessionId).query(Long.class).single();
        // Re-store with correct projectId
        storagePath = fileStorageService.store(file, projectId);

        jdbcTemplate.update("""
                INSERT INTO generation_attachment(session_id, file_name, file_type, file_size, storage_path, content_hash, parse_status, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, '', 'PENDING', ?, ?)
                """, sessionId, originalName, ext, file.getSize(), storagePath, user.id(), now);
        Long id = jdbc.sql("SELECT last_insert_id()").query(Long.class).single();
        return getById(id);
    }

    public List<GenerationAttachmentRecord> listBySession(Long sessionId) {
        return jdbc.sql("SELECT * FROM generation_attachment WHERE session_id = :sid ORDER BY id ASC")
                .param("sid", sessionId).query(this::map).list();
    }

    public GenerationAttachmentRecord getById(Long id) {
        return jdbc.sql("SELECT * FROM generation_attachment WHERE id = :id").param("id", id).query(this::map).single();
    }

    public String getParsedContent(Long attachmentId) {
        var record = getById(attachmentId);
        if ("PARSED".equals(record.parseStatus())) return record.parsedContent();
        if ("PARSING".equals(record.parseStatus())) return "[解析中...]";
        if ("FAILED".equals(record.parseStatus())) return "[解析失败: " + record.parseError() + "]";
        return "[待解析]";
    }

    private GenerationAttachmentRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new GenerationAttachmentRecord(
                rs.getLong("id"), rs.getLong("session_id"),
                rs.getString("message_id") != null ? rs.getLong("message_id") : null,
                rs.getString("file_name"), rs.getString("file_type"),
                rs.getLong("file_size"), rs.getString("storage_path"),
                rs.getString("content_hash"), rs.getString("parse_status"),
                rs.getString("parsed_content"), rs.getString("parse_error"),
                rs.getString("vision_result"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
