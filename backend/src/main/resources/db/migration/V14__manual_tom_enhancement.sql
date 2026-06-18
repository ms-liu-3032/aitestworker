-- V14: test_object_model 扩展手册导入字段 + 手册导入任务表
-- 支持使用手册作为 Mini-TOM 知识源

-- 1. test_object_model 新增字段
ALTER TABLE test_object_model
    ADD COLUMN business_domain VARCHAR(64) NULL
        COMMENT '业务域，如：审批流、CRM、设备巡检' AFTER source_context,
    ADD COLUMN priority VARCHAR(16) NULL DEFAULT 'MEDIUM'
        COMMENT 'P0 | P1 | P2 | P3' AFTER business_domain,
    ADD COLUMN source_doc VARCHAR(512) NULL
        COMMENT '来源文档名称' AFTER priority,
    ADD COLUMN source_section VARCHAR(512) NULL
        COMMENT '来源章节路径（heading_path）' AFTER source_doc,
    ADD COLUMN source_page INT NULL
        COMMENT '来源页码（如有）' AFTER source_section,
    ADD COLUMN evidence_text TEXT NULL
        COMMENT 'LLM抽取的证据原文片段' AFTER source_page,
    ADD COLUMN cross_validation_json JSON NULL
        COMMENT '交叉验证结果 JSON' AFTER evidence_text,
    ADD INDEX idx_tom_domain (project_id, business_domain),
    ADD INDEX idx_tom_source_doc (project_id, source_doc);

-- 2. 手册导入任务跟踪表
CREATE TABLE manual_import_task (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    document_id             BIGINT NULL
        COMMENT 'knowledge_document.id',
    doc_title               VARCHAR(256) NOT NULL,
    business_domain         VARCHAR(64) NOT NULL DEFAULT '',
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING | CHUNKING | EXTRACTING | CROSS_VALIDATING | COMPLETED | FAILED',
    total_chunks            INT NOT NULL DEFAULT 0,
    processed_chunks        INT NOT NULL DEFAULT 0,
    extracted_candidates    INT NOT NULL DEFAULT 0,
    error_message           TEXT NULL,
    created_by              BIGINT NOT NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_mit_project (project_id),
    INDEX idx_mit_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='手册导入任务跟踪';
