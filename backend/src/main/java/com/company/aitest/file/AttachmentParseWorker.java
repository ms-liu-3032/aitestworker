package com.company.aitest.file;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AttachmentParseWorker {

    private static final Logger log = LoggerFactory.getLogger(AttachmentParseWorker.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final FileStorageService fileStorageService;
    private final DocumentParseService documentParseService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final TimeProvider timeProvider;

    public AttachmentParseWorker(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                  FileStorageService fileStorageService,
                                  DocumentParseService documentParseService,
                                  ImageUnderstandingService imageUnderstandingService,
                                  TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.fileStorageService = fileStorageService;
        this.documentParseService = documentParseService;
        this.imageUnderstandingService = imageUnderstandingService;
        this.timeProvider = timeProvider;
    }

    @Scheduled(fixedDelay = 5000)
    public void processPending() {
        List<Map<String, Object>> pending = jdbc.sql(
                        "SELECT id, session_id, file_name, file_type, storage_path FROM generation_attachment WHERE parse_status = 'PENDING' LIMIT 5")
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("session_id", rs.getLong("session_id"));
                    row.put("file_name", rs.getString("file_name"));
                    row.put("file_type", rs.getString("file_type"));
                    row.put("storage_path", rs.getString("storage_path"));
                    return row;
                }).list();

        for (Map<String, Object> row : pending) {
            Long id = ((Number) row.get("id")).longValue();
            String fileType = (String) row.get("file_type");
            String storagePath = (String) row.get("storage_path");
            Long sessionId = ((Number) row.get("session_id")).longValue();

            jdbcTemplate.update("UPDATE generation_attachment SET parse_status = 'PARSING' WHERE id = ?", id);

            try {
                Path filePath = fileStorageService.resolve(storagePath);
                String parsed = null;
                String visionResult = null;

                if (isDocument(fileType)) {
                    parsed = documentParseService.parse(filePath.toString(), fileType);
                } else if (isImage(fileType)) {
                    // Try vision analysis - get model config from session
                    Long modelConfigId = jdbc.sql(
                                    "SELECT model_config_id FROM generation_session WHERE id = :sid")
                            .param("sid", sessionId).query((rs, rowNum) -> (Long) rs.getLong("model_config_id")).single();
                    if (modelConfigId != null && imageUnderstandingService.supportsVision(modelConfigId)) {
                        visionResult = imageUnderstandingService.analyzeImage(filePath, modelConfigId, 0L, null);
                    }
                }

                jdbcTemplate.update(
                        "UPDATE generation_attachment SET parse_status = 'PARSED', parsed_content = ?, vision_result = ? WHERE id = ?",
                        parsed, visionResult, id);
            } catch (Exception e) {
                log.warn("Attachment parse failed for id={}: {}", id, e.getMessage());
                jdbcTemplate.update(
                        "UPDATE generation_attachment SET parse_status = 'FAILED', parse_error = ? WHERE id = ?",
                        e.getMessage(), id);
            }
        }
    }

    private boolean isDocument(String fileType) {
        if (fileType == null) return false;
        return switch (fileType.toLowerCase()) {
            case "pdf", "doc", "docx" -> true;
            default -> false;
        };
    }

    private boolean isImage(String fileType) {
        if (fileType == null) return false;
        return switch (fileType.toLowerCase()) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp" -> true;
            default -> false;
        };
    }
}
