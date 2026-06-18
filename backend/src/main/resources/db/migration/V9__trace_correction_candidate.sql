-- =====================================================================
-- V9  Trace Correction Candidate — 轨迹定位与语义修正建议承载层
-- Plan ref: docs/handover/11_轨迹定位与语义修正建议设计方案.md §6
--
-- 原则：新增专用表，不与 trace_summary / knowledge_asset 混表。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 修正建议候选表
-- ---------------------------------------------------------------------
CREATE TABLE trace_correction_candidate (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    trace_group_id          BIGINT NOT NULL,
    trace_session_id        BIGINT NULL,
    issue_clip_id           BIGINT NULL,
    trace_event_id          BIGINT NULL,
    summary_id              BIGINT NULL,

    correction_type         VARCHAR(32) NOT NULL
        COMMENT 'OBJECT_LABEL | CHECKBOX_SEMANTICS | DIALOG_ACTION | INPUT_FINAL_VALUE | BUSINESS_ACTION_MAPPING',
    source_text             TEXT NOT NULL
        COMMENT '原始文本（如 element_text, value_summary 等）',
    candidate_value         TEXT NOT NULL
        COMMENT '候选修正值',
    candidate_reason        TEXT NOT NULL
        COMMENT '候选原因说明',
    confidence_label        VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'
        COMMENT 'HIGH | MEDIUM | LOW',

    status                  VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE'
        COMMENT 'CANDIDATE | CONFIRMED | REJECTED',

    raw_context_snapshot    LONGTEXT NULL
        COMMENT '生成时的原始上下文快照（轨迹事件片段）',
    prompt_snapshot_id      BIGINT NULL
        COMMENT '关联 prompt_snapshot',
    context_manifest_id     BIGINT NULL
        COMMENT '关联 context_manifest',
    llm_invocation_log_id   BIGINT NULL
        COMMENT '关联 llm_invocation_log',

    created_by              BIGINT NOT NULL,
    confirmed_by            BIGINT NULL,
    confirmed_at            DATETIME NULL,
    rejected_by             BIGINT NULL,
    rejected_at             DATETIME NULL,
    rejected_reason         TEXT NULL,

    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='轨迹定位与语义修正建议候选层。只有 CONFIRMED 才允许参与摘要/用例/Mini-TOM';

-- ---------------------------------------------------------------------
-- 2. 索引
-- ---------------------------------------------------------------------
CREATE INDEX idx_tcc_group_status    ON trace_correction_candidate (trace_group_id, status);
CREATE INDEX idx_tcc_event           ON trace_correction_candidate (trace_event_id);
CREATE INDEX idx_tcc_summary         ON trace_correction_candidate (summary_id);
CREATE INDEX idx_tcc_type            ON trace_correction_candidate (correction_type);
CREATE INDEX idx_tcc_status_conf     ON trace_correction_candidate (status, confidence_label);
CREATE INDEX idx_tcc_created         ON trace_correction_candidate (project_id, created_at);
CREATE INDEX idx_tcc_prompt_snap     ON trace_correction_candidate (prompt_snapshot_id);
CREATE INDEX idx_tcc_manifest        ON trace_correction_candidate (context_manifest_id);
CREATE INDEX idx_tcc_invocation      ON trace_correction_candidate (llm_invocation_log_id);
