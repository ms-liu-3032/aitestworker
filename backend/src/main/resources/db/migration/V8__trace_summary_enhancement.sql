-- =====================================================================
-- V8  Trace Summary Textualization P0 — trace_summary 正式承载层扩展
-- Plan ref: docs/handover/10_轨迹摘要文本化P0设计与实现方案.md §14
--
-- 原则：优先 ALTER，保留旧字段兼容，新增结构化字段与索引。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. trace_summary 新增结构化字段
-- ---------------------------------------------------------------------
ALTER TABLE trace_summary
    ADD COLUMN trace_session_id BIGINT NULL AFTER trace_group_id,
    ADD COLUMN issue_clip_id BIGINT NULL AFTER trace_session_id,
    ADD COLUMN summary_scope VARCHAR(32) NOT NULL DEFAULT 'GROUP' AFTER issue_clip_id,
    ADD COLUMN overview TEXT NULL AFTER summary_scope,
    ADD COLUMN business_summary TEXT NULL AFTER overview,
    ADD COLUMN key_steps_json LONGTEXT NULL AFTER business_summary,
    ADD COLUMN key_api_json LONGTEXT NULL AFTER key_steps_json,
    ADD COLUMN exception_summary TEXT NULL AFTER key_api_json,
    ADD COLUMN case_generation_suggestion_json LONGTEXT NULL AFTER exception_summary,
    ADD COLUMN raw_input_snapshot LONGTEXT NULL AFTER case_generation_suggestion_json,
    ADD COLUMN llm_output_snapshot LONGTEXT NULL AFTER raw_input_snapshot,
    ADD COLUMN model_config_id BIGINT NULL AFTER llm_output_snapshot,
    ADD COLUMN prompt_snapshot_id BIGINT NULL AFTER model_config_id,
    ADD COLUMN context_manifest_id BIGINT NULL AFTER prompt_snapshot_id,
    ADD COLUMN llm_invocation_log_id BIGINT NULL AFTER context_manifest_id,
    ADD COLUMN validity_label VARCHAR(32) NOT NULL DEFAULT 'TO_CONFIRM' AFTER status,
    ADD COLUMN confirmed_by BIGINT NULL AFTER created_by,
    ADD COLUMN confirmed_at DATETIME NULL AFTER confirmed_by,
    ADD COLUMN rejected_by BIGINT NULL AFTER confirmed_at,
    ADD COLUMN rejected_at DATETIME NULL AFTER rejected_by,
    ADD COLUMN rejected_reason TEXT NULL AFTER rejected_at,
    ADD COLUMN pending_confirmation_json LONGTEXT NULL AFTER rejected_reason,
    ADD COLUMN confidence_label VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' AFTER pending_confirmation_json;

-- ---------------------------------------------------------------------
-- 2. 新增索引（与方案 §4.4 对齐）
-- ---------------------------------------------------------------------
ALTER TABLE trace_summary
    ADD INDEX idx_ts_session (trace_session_id),
    ADD INDEX idx_ts_clip (issue_clip_id),
    ADD INDEX idx_ts_scope (summary_scope),
    ADD INDEX idx_ts_status_validity (status, validity_label),
    ADD INDEX idx_ts_created (project_id, created_at),
    ADD INDEX idx_ts_model (model_config_id),
    ADD INDEX idx_ts_prompt_snap (prompt_snapshot_id),
    ADD INDEX idx_ts_manifest (context_manifest_id),
    ADD INDEX idx_ts_invocation (llm_invocation_log_id);

-- ---------------------------------------------------------------------
-- 3. 保留旧字段说明（已由 V7 创建，此处不修改）
--    - summary_text      → 兼容全文拼接展示
--    - scope             → 过渡期兼容，逐步映射到 summary_scope
--    - review_status     → 过渡期兼容旧逻辑
--    - trust_level       → 治理辅助字段
-- ---------------------------------------------------------------------
