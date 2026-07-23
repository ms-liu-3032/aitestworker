package com.company.aitest.file;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.TimeProvider;
import com.company.aitest.knowledge.KnowledgeDepositionService;
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
    private KnowledgeDepositionService knowledgeDepositionService;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKnowledgeDepositionService(KnowledgeDepositionService knowledgeDepositionService) {
        this.knowledgeDepositionService = knowledgeDepositionService;
    }

    @Scheduled(fixedDelay = 5000)
    public void processPending() {
        List<Map<String, Object>> pending = jdbc.sql(
                        "SELECT id, session_id, file_name, file_type, storage_path, created_by FROM generation_attachment WHERE parse_status = 'PENDING' LIMIT 5")
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("session_id", rs.getLong("session_id"));
                    row.put("file_name", rs.getString("file_name"));
                    row.put("file_type", rs.getString("file_type"));
                    row.put("storage_path", rs.getString("storage_path"));
                    row.put("created_by", rs.getLong("created_by"));
                    return row;
                }).list();

        for (Map<String, Object> row : pending) {
            Long id = ((Number) row.get("id")).longValue();
            String fileType = (String) row.get("file_type");
            String storagePath = (String) row.get("storage_path");
            Long sessionId = ((Number) row.get("session_id")).longValue();

            int claimed = jdbcTemplate.update(
                    "UPDATE generation_attachment SET parse_status = 'PARSING' WHERE id = ? AND parse_status = 'PENDING'",
                    id);
            if (claimed == 0) {
                continue;
            }

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
                            .param("sid", sessionId).query((rs, rowNum) -> {
                                long value = rs.getLong("model_config_id");
                                return rs.wasNull() ? null : value;
                            }).single();
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
        processKnowledgeDeposition();
    }

    private void processKnowledgeDeposition() {
        if (knowledgeDepositionService == null) return;
        List<Map<String, Object>> pending = jdbc.sql("""
                        SELECT ga.id, ga.session_id, ga.file_name, ga.created_by,
                               COALESCE(NULLIF(ga.parsed_content, ''), NULLIF(ga.vision_result, '')) AS deposit_content,
                               gs.project_id
                        FROM generation_attachment ga
                        JOIN generation_session gs ON gs.id = ga.session_id
                        WHERE ga.parse_status = 'PARSED'
                          AND (ga.knowledge_deposition_status IN ('PENDING', 'FAILED')
                               OR (ga.knowledge_deposition_status = 'RUNNING'
                                   AND ga.knowledge_deposition_started_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)))
                          AND ga.knowledge_deposition_attempts < 3
                        ORDER BY ga.id
                        LIMIT 5
                        """)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("file_name", rs.getString("file_name"));
                    row.put("created_by", rs.getLong("created_by"));
                    row.put("project_id", rs.getLong("project_id"));
                    row.put("deposit_content", rs.getString("deposit_content"));
                    return row;
                }).list();

        for (Map<String, Object> row : pending) {
            Long id = ((Number) row.get("id")).longValue();
            String content = (String) row.get("deposit_content");
            if (content == null || content.isBlank()) {
                jdbcTemplate.update("""
                        UPDATE generation_attachment
                        SET knowledge_deposition_status = 'SKIPPED', knowledge_deposition_error = NULL
                        WHERE id = ? AND knowledge_deposition_status IN ('PENDING', 'FAILED')
                        """, id);
                continue;
            }
            int claimed = jdbcTemplate.update("""
                    UPDATE generation_attachment
                    SET knowledge_deposition_status = 'RUNNING',
                        knowledge_deposition_attempts = knowledge_deposition_attempts + 1,
                        knowledge_deposition_started_at = ?,
                        knowledge_deposition_error = NULL
                    WHERE id = ? AND (knowledge_deposition_status IN ('PENDING', 'FAILED')
                        OR (knowledge_deposition_status = 'RUNNING'
                            AND knowledge_deposition_started_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)))
                    """, timeProvider.now(), id);
            if (claimed == 0) continue;
            try {
                knowledgeDepositionService.depositUploadedDocument(
                        ((Number) row.get("project_id")).longValue(), "GENERATION_ATTACHMENT", id,
                        String.valueOf(row.get("file_name")), content,
                        ((Number) row.get("created_by")).longValue());
                jdbcTemplate.update("""
                        UPDATE generation_attachment
                        SET knowledge_deposition_status = 'SUCCEEDED', knowledge_deposition_error = NULL,
                            knowledge_deposited_at = ?
                        WHERE id = ? AND knowledge_deposition_status = 'RUNNING'
                        """, timeProvider.now(), id);
            } catch (Exception e) {
                log.warn("Attachment knowledge deposition failed for id={}: {}", id, e.getMessage());
                jdbcTemplate.update("""
                        UPDATE generation_attachment
                        SET knowledge_deposition_status = 'FAILED', knowledge_deposition_error = ?
                        WHERE id = ? AND knowledge_deposition_status = 'RUNNING'
                        """, clipError(e.getMessage()), id);
            }
        }
    }

    private String clipError(String message) {
        if (message == null) return "未知错误";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
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
