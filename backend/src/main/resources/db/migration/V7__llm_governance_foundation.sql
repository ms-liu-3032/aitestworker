-- =====================================================================
-- V7  Multi-user LLM data-pollution governance — schema foundation
-- Plan ref: docs/handover/07_LLM数据污染治理方案.md §18.1
-- Sprint 1 · M1.1
--
-- 本次只加表与默认值，旧代码无须变更即可继续运行。
-- 后续版本（V8+）做数据回填与状态机收紧。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. prompt_snapshot — 每次 LLM 调用的渲染快照
-- ---------------------------------------------------------------------
CREATE TABLE prompt_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    task_id BIGINT,
    stage VARCHAR(64) NOT NULL,
    prompt_template_id BIGINT,
    prompt_version INT,
    rendered_system LONGTEXT,
    rendered_user LONGTEXT,
    variables_json TEXT,
    content_hash VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_ps_task (task_id),
    INDEX idx_ps_user (user_id),
    INDEX idx_ps_request (request_id),
    INDEX idx_ps_hash (content_hash)
);

-- ---------------------------------------------------------------------
-- 2. context_manifest — 每次 LLM 调用所用上下文的清单
-- ---------------------------------------------------------------------
CREATE TABLE context_manifest (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    task_id BIGINT,
    stage VARCHAR(64) NOT NULL,
    model_config_id BIGINT,
    prompt_template_id BIGINT,
    prompt_version INT,
    included_assets_json LONGTEXT,
    excluded_policy_json TEXT,
    conflicts_json TEXT,
    created_at DATETIME NOT NULL,
    INDEX idx_cm_task (task_id),
    INDEX idx_cm_user (user_id),
    INDEX idx_cm_request (request_id)
);

-- ---------------------------------------------------------------------
-- 3. llm_invocation_log — 主审计表
-- ---------------------------------------------------------------------
CREATE TABLE llm_invocation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT,
    task_id BIGINT,
    task_type VARCHAR(32),
    stage VARCHAR(64) NOT NULL,
    model_config_id BIGINT NOT NULL,
    prompt_template_id BIGINT,
    prompt_version INT,
    prompt_snapshot_id BIGINT,
    input_snapshot_ref VARCHAR(512),
    output_snapshot_ref VARCHAR(512),
    context_manifest_id BIGINT,
    token_input INT NOT NULL DEFAULT 0,
    token_output INT NOT NULL DEFAULT 0,
    cost_amount DECIMAL(12,6),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    error_message TEXT,
    duration_ms BIGINT,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_llm_log_request (request_id),
    INDEX idx_llm_log_user (user_id),
    INDEX idx_llm_log_project (project_id),
    INDEX idx_llm_log_task (task_id),
    INDEX idx_llm_log_stage (stage),
    INDEX idx_llm_log_status (status),
    INDEX idx_llm_log_created (created_at)
);

-- ---------------------------------------------------------------------
-- 4. security_event_log — Prompt 注入 / 越权 / 限流告警
-- ---------------------------------------------------------------------
CREATE TABLE security_event_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    user_id BIGINT,
    project_id BIGINT,
    task_id BIGINT,
    request_id VARCHAR(64),
    detail_json TEXT,
    created_at DATETIME NOT NULL,
    INDEX idx_sec_event_type (event_type),
    INDEX idx_sec_severity (severity),
    INDEX idx_sec_created (created_at)
);

-- ---------------------------------------------------------------------
-- 5. test_skill_template / test_tool_template —— V2 已建，加治理字段
--    （之前误以为新表，本次改为对原表 ALTER，避免重复）
-- ---------------------------------------------------------------------
ALTER TABLE test_skill_template
    ADD COLUMN scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL' AFTER project_id,
    ADD COLUMN description TEXT NULL AFTER applicable_scene,
    ADD COLUMN body LONGTEXT NULL AFTER flow_steps,
    ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER version,
    ADD COLUMN trust_level VARCHAR(32) NOT NULL DEFAULT 'AI_GENERATED' AFTER review_status,
    ADD COLUMN deprecated_at DATETIME NULL,
    ADD COLUMN deprecated_by BIGINT NULL,
    ADD INDEX idx_skill_scope_status (scope, status),
    ADD INDEX idx_skill_review (review_status);

ALTER TABLE test_tool_template
    ADD COLUMN scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL' AFTER project_id,
    ADD COLUMN description TEXT NULL AFTER tool_name,
    ADD COLUMN body LONGTEXT NULL AFTER operation_steps,
    ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER version,
    ADD COLUMN trust_level VARCHAR(32) NOT NULL DEFAULT 'AI_GENERATED' AFTER review_status,
    ADD COLUMN deprecated_at DATETIME NULL,
    ADD COLUMN deprecated_by BIGINT NULL,
    ADD INDEX idx_tool_scope_status (scope, status),
    ADD INDEX idx_tool_review (review_status);

-- ---------------------------------------------------------------------
-- 6. trace_summary / trace_asset_tag — 轨迹衍生资产
-- ---------------------------------------------------------------------
CREATE TABLE trace_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_group_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    summary_text LONGTEXT,
    scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    trust_level VARCHAR(32) NOT NULL DEFAULT 'AI_GENERATED',
    created_by BIGINT NOT NULL,
    deprecated_at DATETIME,
    deprecated_by BIGINT,
    deprecated_reason TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_ts_group (trace_group_id),
    INDEX idx_ts_project (project_id),
    INDEX idx_ts_scope_status (scope, status)
);

CREATE TABLE trace_asset_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    tag VARCHAR(32) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_tag (target_type, target_id, tag),
    INDEX idx_tag_target (target_type, target_id)
);

-- ---------------------------------------------------------------------
-- 7. vector_index_outbox — 写向量库的唯一通道
-- ---------------------------------------------------------------------
CREATE TABLE vector_index_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_type VARCHAR(64) NOT NULL,
    asset_id BIGINT NOT NULL,
    project_id BIGINT,
    operation VARCHAR(16) NOT NULL,
    payload_json LONGTEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    available_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_vio_status_time (status, available_at),
    INDEX idx_vio_asset (asset_type, asset_id)
);

-- ---------------------------------------------------------------------
-- 8. llm_quota_config / llm_quota_usage_day — 限流与预算
-- ---------------------------------------------------------------------
CREATE TABLE llm_quota_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scope_type VARCHAR(32) NOT NULL,
    scope_id BIGINT,
    stage VARCHAR(64),
    daily_token_limit INT,
    per_call_input_limit INT,
    per_call_output_limit INT,
    concurrent_limit INT,
    rate_limit_per_min INT,
    enabled TINYINT NOT NULL DEFAULT 1,
    updated_by BIGINT,
    updated_at DATETIME NOT NULL,
    INDEX idx_quota_scope (scope_type, scope_id, stage)
);

CREATE TABLE llm_quota_usage_day (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    project_id BIGINT,
    stat_date DATE NOT NULL,
    token_input INT NOT NULL DEFAULT 0,
    token_output INT NOT NULL DEFAULT 0,
    call_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_quota_day (user_id, project_id, stat_date),
    INDEX idx_quota_date (stat_date)
);

-- =====================================================================
-- 表字段扩展（ALTER）—— 默认值确保旧代码不需要修改即可继续运行
-- =====================================================================

-- prompt_template：加入 version、review_status、deprecated_*
ALTER TABLE prompt_template
    ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER content_hash,
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED' AFTER status,
    ADD COLUMN deprecated_at DATETIME NULL,
    ADD COLUMN deprecated_by BIGINT NULL,
    ADD COLUMN deprecated_reason TEXT NULL;
-- 旧索引 uk_prompt_hash 改为 (prompt_name, version, scope)
ALTER TABLE prompt_template
    DROP INDEX uk_prompt_hash,
    ADD UNIQUE KEY uk_prompt_name_ver_scope (prompt_name, version, scope),
    ADD INDEX idx_prompt_hash (content_hash);

-- knowledge_document：加入 scope/visibility/review_status/trust_level
ALTER TABLE knowledge_document
    ADD COLUMN scope VARCHAR(32) NOT NULL DEFAULT 'PROJECT',
    ADD COLUMN visibility VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN trust_level VARCHAR(32) NOT NULL DEFAULT 'YUQUE_DOC',
    ADD COLUMN deprecated_at DATETIME NULL,
    ADD COLUMN deprecated_by BIGINT NULL,
    ADD COLUMN deprecated_reason TEXT NULL;

-- knowledge_chunk：弃用标记，便于检索快速过滤
ALTER TABLE knowledge_chunk
    ADD COLUMN deprecated TINYINT NOT NULL DEFAULT 0,
    ADD INDEX idx_kc_deprecated (deprecated);

-- test_case_asset：V2 已加入 scope/status/submitted/exported 基础字段，
-- 本次只补治理相关字段，避免与 V2 重复。
ALTER TABLE test_case_asset
    ADD COLUMN prompt_snapshot_id BIGINT NULL,
    ADD COLUMN model_config_id BIGINT NULL,
    ADD COLUMN trust_level VARCHAR(32) NOT NULL DEFAULT 'HISTORICAL_CASE',
    ADD COLUMN deprecated_at DATETIME NULL,
    ADD COLUMN deprecated_by BIGINT NULL,
    ADD COLUMN deprecated_reason TEXT NULL,
    ADD INDEX idx_tca_scope_status (case_scope, case_status),
    ADD INDEX idx_tca_deprecated (deprecated_at);

-- test_case_draft：加入 scope/status/created_by
ALTER TABLE test_case_draft
    ADD COLUMN case_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    ADD COLUMN case_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN created_by BIGINT NOT NULL DEFAULT 0,
    ADD INDEX idx_tcd_owner (created_by);

-- test_point_draft：加入 scope/created_by
ALTER TABLE test_point_draft
    ADD COLUMN case_scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    ADD COLUMN created_by BIGINT NOT NULL DEFAULT 0,
    ADD INDEX idx_tpd_owner (created_by);

-- generation_task：created_by 基础字段已存在，本次只补治理链路所需字段
ALTER TABLE generation_task
    ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT 'GENERATION',
    ADD COLUMN prompt_template_id BIGINT NULL,
    ADD COLUMN prompt_version INT NULL,
    ADD COLUMN prompt_snapshot_id BIGINT NULL,
    ADD INDEX idx_gen_task_type (task_type),
    ADD INDEX idx_gen_task_owner (created_by);

-- skill_execution_log：补 request_id/user_id/context_manifest_id/skill_template_id/skill_version
ALTER TABLE skill_execution_log
    ADD COLUMN request_id VARCHAR(64) NULL,
    ADD COLUMN user_id BIGINT NULL,
    ADD COLUMN context_manifest_id BIGINT NULL,
    ADD COLUMN skill_template_id BIGINT NULL,
    ADD COLUMN skill_version INT NULL,
    ADD INDEX idx_sel_request (request_id),
    ADD INDEX idx_sel_user (user_id);
