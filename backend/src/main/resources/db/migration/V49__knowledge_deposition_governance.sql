-- Wiki/TOM 自动沉淀治理：候选来源、版本、审核与生效门槛

ALTER TABLE wiki_pack
    ADD COLUMN reviewed_by BIGINT NULL AFTER created_by,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by;

ALTER TABLE wiki_entry
    MODIFY COLUMN content MEDIUMTEXT NOT NULL,
    ADD COLUMN candidate_key VARCHAR(191) NULL AFTER source_refs_json,
    ADD COLUMN source_type VARCHAR(32) NULL AFTER candidate_key,
    ADD COLUMN source_ref_type VARCHAR(32) NULL AFTER source_type,
    ADD COLUMN source_ref_id BIGINT NULL AFTER source_ref_type,
    ADD COLUMN source_version INT NOT NULL DEFAULT 1 AFTER source_ref_id,
    ADD COLUMN content_hash CHAR(64) NULL AFTER source_version,
    ADD COLUMN approved_by BIGINT NULL AFTER created_by,
    ADD COLUMN approved_at DATETIME NULL AFTER approved_by,
    ADD COLUMN rejected_by BIGINT NULL AFTER approved_at,
    ADD COLUMN rejected_at DATETIME NULL AFTER rejected_by,
    ADD COLUMN rejected_reason VARCHAR(512) NULL AFTER rejected_at,
    ADD INDEX idx_wiki_entry_candidate (pack_id, candidate_key, source_version),
    ADD INDEX idx_wiki_entry_source (source_type, source_ref_type, source_ref_id),
    ADD INDEX idx_wiki_entry_effective_review (effective_status, review_status);

ALTER TABLE test_object_model
    ADD COLUMN candidate_key VARCHAR(191) NULL AFTER source_refs_json,
    ADD COLUMN source_hash CHAR(64) NULL AFTER candidate_key,
    ADD COLUMN source_version INT NOT NULL DEFAULT 1 AFTER source_hash,
    ADD INDEX idx_tom_candidate_key (project_id, candidate_key, source_version),
    ADD INDEX idx_tom_source_hash (project_id, source_hash);

ALTER TABLE generation_attachment
    ADD COLUMN knowledge_deposition_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER parse_error,
    ADD COLUMN knowledge_deposition_attempts INT NOT NULL DEFAULT 0 AFTER knowledge_deposition_status,
    ADD COLUMN knowledge_deposition_started_at DATETIME NULL AFTER knowledge_deposition_attempts,
    ADD COLUMN knowledge_deposition_error VARCHAR(1000) NULL AFTER knowledge_deposition_started_at,
    ADD COLUMN knowledge_deposited_at DATETIME NULL AFTER knowledge_deposition_error,
    ADD INDEX idx_generation_attachment_deposition (parse_status, knowledge_deposition_status, knowledge_deposition_attempts);

ALTER TABLE manual_import_task
    ADD COLUMN knowledge_deposition_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER error_message,
    ADD COLUMN knowledge_deposition_attempts INT NOT NULL DEFAULT 0 AFTER knowledge_deposition_status,
    ADD COLUMN knowledge_deposition_started_at DATETIME NULL AFTER knowledge_deposition_attempts,
    ADD COLUMN knowledge_deposition_error VARCHAR(1000) NULL AFTER knowledge_deposition_started_at,
    ADD COLUMN knowledge_deposited_at DATETIME NULL AFTER knowledge_deposition_error;

-- 历史数据也必须满足“审核通过后才能生效”。
UPDATE wiki_entry
SET effective_status = CASE WHEN review_status = 'APPROVED' THEN 'ACTIVE' ELSE 'INACTIVE' END;

UPDATE wiki_pack
SET status = 'DRAFT'
WHERE status = 'ACTIVE' AND review_status <> 'APPROVED';
