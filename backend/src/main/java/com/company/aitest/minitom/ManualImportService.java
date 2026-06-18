package com.company.aitest.minitom;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.knowledge.KnowledgeChunk;
import com.company.aitest.knowledge.KnowledgeChunker;
import com.company.aitest.knowledge.KnowledgeTextCleaner;
import com.company.aitest.preset.DomainPresetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用手册导入服务。
 * <p>
 * 编排流程：Markdown 存储 → 切片 → LLM 抽取 → 候选插入 → 交叉验证。
 */
@Service
public class ManualImportService {

    private static final Logger log = LoggerFactory.getLogger(ManualImportService.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeChunker chunker;
    private final KnowledgeTextCleaner cleaner;
    private final TomLlmExtractor extractor;
    private final MiniTomCrossValidationService crossValidationService;
    private final TimeProvider timeProvider;
    private final DomainPresetService domainPresetService;

    public ManualImportService(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                               TomLlmExtractor extractor,
                               MiniTomCrossValidationService crossValidationService,
                               TimeProvider timeProvider,
                               DomainPresetService domainPresetService) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.chunker = new KnowledgeChunker();
        this.cleaner = new KnowledgeTextCleaner();
        this.extractor = extractor;
        this.crossValidationService = crossValidationService;
        this.timeProvider = timeProvider;
        this.domainPresetService = domainPresetService;
    }

    /**
     * 导入使用手册，抽取 Mini-TOM 候选。
     * <p>
     * LLM 调用不在事务中，文档/切片存储使用短事务。
     */
    public ImportProgressResponse importManual(ImportManualCommand cmd, CurrentUser user) {
        if (cmd.projectId() == null || cmd.docTitle() == null || cmd.docTitle().isBlank()) {
            throw new BusinessException("项目 ID 和文档标题不能为空");
        }
        if (cmd.markdownContent() == null || cmd.markdownContent().isBlank()) {
            throw new BusinessException("手册内容不能为空");
        }
        if (cmd.modelConfigId() == null) {
            throw new BusinessException("请选择 LLM 模型");
        }

        String defaultDomain = domainPresetService.defaultPreset().defaultBusinessDomain();
        // 1. 存储文档 + 切片（短事务）
        Long[] ids = storeDocumentAndChunks(cmd.projectId(), cmd.docTitle(), cmd.markdownContent(),
                cmd.businessDomain() != null ? cmd.businessDomain() : defaultDomain, user.id());
        Long docId = ids[0];
        Long taskId = ids[1];

        // 2. 切片内容（内存操作）
        String cleaned = cleaner.clean(cmd.markdownContent());
        List<KnowledgeChunk> chunks = chunker.chunk(cleaned);
        updateTaskProgress(taskId, "CHUNKING", chunks.size(), 0, 0);

        // 3. 逐 chunk LLM 抽取（不在事务中，每个候选独立提交）
        String domain = cmd.businessDomain() != null ? cmd.businessDomain() : defaultDomain;
        int extracted = extractFromChunks(taskId, cmd.projectId(), domain, cmd.docTitle(),
                chunks, cmd.modelConfigId(), user);

        // 4. 交叉验证
        updateTaskProgress(taskId, "CROSS_VALIDATING", chunks.size(), chunks.size(), extracted);
        try {
            crossValidationService.crossValidateProject(cmd.projectId(), user);
        } catch (Exception e) {
            log.warn("交叉验证失败（非致命）: {}", e.getMessage());
        }

        // 5. 完成
        updateTaskProgress(taskId, "COMPLETED", chunks.size(), chunks.size(), extracted);
        return getProgress(taskId);
    }

    @Transactional
    protected Long[] storeDocumentAndChunks(Long projectId, String docTitle, String markdown,
                                             String businessDomain, Long userId) {
        Long docId = storeKnowledgeDocument(projectId, docTitle, markdown);
        Long taskId = createImportTask(projectId, docId, docTitle, businessDomain, userId);
        String cleaned = cleaner.clean(markdown);
        List<KnowledgeChunk> chunks = chunker.chunk(cleaned);
        storeChunks(docId, projectId, chunks);
        return new Long[]{docId, taskId};
    }

    /**
     * 查询导入进度。
     */
    public ImportProgressResponse getProgress(Long taskId) {
        return jdbc.sql("""
                        SELECT id, status, total_chunks, processed_chunks, extracted_candidates, error_message
                        FROM manual_import_task WHERE id = :id
                        """).param("id", taskId)
                .query(this::mapProgress).single();
    }

    /**
     * 列出项目的导入任务。
     */
    public List<ImportProgressResponse> listTasks(Long projectId) {
        return jdbc.sql("""
                        SELECT id, status, total_chunks, processed_chunks, extracted_candidates, error_message
                        FROM manual_import_task WHERE project_id = :projectId ORDER BY id DESC
                        """).param("projectId", projectId)
                .query(this::mapProgress).list();
    }

    // =====================================================================
    // 内部方法
    // =====================================================================

    private Long storeKnowledgeDocument(Long projectId, String title, String markdown) {
        String contentHash = KnowledgeChunker.hash(markdown);
        String sourceDocId = "manual_" + System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT INTO knowledge_document(
                    project_id, source_type, source_doc_id, title,
                    markdown_content, plain_text, content_hash,
                    doc_status, created_at, updated_at
                ) VALUES (?, 'MANUAL_IMPORT', ?, ?, ?, ?, ?, 'IMPORTED', ?, ?)
                """, projectId, sourceDocId, title, markdown, markdown, contentHash,
                timeProvider.now(), timeProvider.now());
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private Long createImportTask(Long projectId, Long docId, String docTitle,
                                   String businessDomain, Long userId) {
        jdbcTemplate.update("""
                INSERT INTO manual_import_task(
                    project_id, document_id, doc_title, business_domain,
                    status, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """, projectId, docId, docTitle, businessDomain,
                userId, timeProvider.now(), timeProvider.now());
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void storeChunks(Long docId, Long projectId, List<KnowledgeChunk> chunks) {
        for (KnowledgeChunk chunk : chunks) {
            String chunkTitle = extractTitle(chunk.headingPath());
            jdbcTemplate.update("""
                    INSERT INTO knowledge_chunk(
                        project_id, document_id, chunk_no, chunk_title,
                        heading_path, chunk_content, content_hash,
                        vector_status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """, projectId, docId, chunk.chunkNo(), chunkTitle,
                    chunk.headingPath(), chunk.content(), chunk.contentHash(),
                    timeProvider.now(), timeProvider.now());
        }
    }

    private int extractFromChunks(Long taskId, Long projectId, String businessDomain,
                                   String docTitle, List<KnowledgeChunk> chunks,
                                   Long modelConfigId, CurrentUser user) {
        int totalExtracted = 0;
        LocalDateTime now = timeProvider.now();

        for (int i = 0; i < chunks.size(); i++) {
            try {
                List<TomLlmExtractor.TomExtractionResult> results = extractor.extractFromChunk(
                        taskId, projectId, businessDomain, docTitle, chunks.get(i), modelConfigId, user);

                for (TomLlmExtractor.TomExtractionResult result : results) {
                    insertManualCandidate(projectId, businessDomain, docTitle,
                            chunks.get(i), result, user.id(), now);
                    totalExtracted++;
                }
            } catch (Exception e) {
                log.warn("Chunk {} 抽取失败: {}", chunks.get(i).chunkNo(), e.getMessage());
            }
            updateTaskProgress(taskId, "EXTRACTING", chunks.size(), i + 1, totalExtracted);
        }
        return totalExtracted;
    }

    private void insertManualCandidate(Long projectId, String businessDomain, String docTitle,
                                        KnowledgeChunk chunk, TomLlmExtractor.TomExtractionResult result,
                                        Long userId, LocalDateTime now) {
        String modelType = normalizeModelType(result.modelType());
        String name = truncate(result.name() != null ? result.name() : "未命名", 256);
        String description = truncate(result.description() != null ? result.description() : "", 2000);
        String evidence = truncate(result.evidenceText() != null ? result.evidenceText() : "", 2000);
        BigDecimal confidence = result.confidence() != null ? result.confidence() : new BigDecimal("0.50");
        String priority = normalizePriority(result.priority());

        jdbcTemplate.update("""
                INSERT INTO test_object_model(
                    project_id, model_type, name, description, properties_json,
                    source_type, source_ref_id, source_context,
                    business_domain, priority, source_doc, source_section, evidence_text,
                    confidence, status, requires_human_confirm, validity_label,
                    created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, NULL, 'MANUAL_IMPORT', NULL, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', 1, 'TO_CONFIRM', ?, ?, ?)
                """, projectId, modelType, name, description,
                description.substring(0, Math.min(description.length(), 500)),
                businessDomain, priority, docTitle, chunk.headingPath(), evidence,
                confidence, userId, now, now);
    }

    private void updateTaskProgress(Long taskId, String status, int totalChunks,
                                     int processedChunks, int extractedCandidates) {
        jdbcTemplate.update("""
                UPDATE manual_import_task SET
                    status = ?, total_chunks = ?, processed_chunks = ?,
                    extracted_candidates = ?, updated_at = ?
                WHERE id = ?
                """, status, totalChunks, processedChunks, extractedCandidates,
                timeProvider.now(), taskId);
    }

    private String extractTitle(String headingPath) {
        if (headingPath == null || headingPath.isBlank()) return "";
        String[] parts = headingPath.split(" > ");
        return parts[parts.length - 1];
    }

    private String normalizeModelType(String type) {
        if (type == null) return "PAGE";
        return switch (type.toUpperCase()) {
            case "MODULE", "PAGE", "FIELD", "ACTION", "FLOW", "STATE", "ASSERTION" -> type.toUpperCase();
            default -> "PAGE";
        };
    }

    private String normalizePriority(String priority) {
        if (priority == null) return "P2";
        return switch (priority.toUpperCase()) {
            case "P0", "P1", "P2", "P3" -> priority.toUpperCase();
            default -> "P2";
        };
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    private ImportProgressResponse mapProgress(ResultSet rs, int rowNum) throws SQLException {
        return new ImportProgressResponse(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getInt("total_chunks"),
                rs.getInt("processed_chunks"),
                rs.getInt("extracted_candidates"),
                rs.getString("error_message"));
    }

    // =====================================================================
    // DTO
    // =====================================================================

    public record ImportManualCommand(
            Long projectId,
            String docTitle,
            String businessDomain,
            String markdownContent,
            Long modelConfigId
    ) {
    }

    public record ImportProgressResponse(
            Long importTaskId,
            String status,
            int totalChunks,
            int processedChunks,
            int extractedCandidates,
            String errorMessage
    ) {
    }
}
