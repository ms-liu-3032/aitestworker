-- =====================================================================
-- V11  Trace Correction — 步骤级修正 + 模式学习辅助字段
-- Plan ref: docs/handover/11_轨迹定位与语义修正建议设计方案.md §6
--
-- 目标：支持"字段级修正 + 步骤级修正 + 模式学习辅助"
-- =====================================================================

-- 1. 步骤级修正字段
ALTER TABLE trace_correction_candidate
    ADD COLUMN step_no INT NULL
        COMMENT '关联的清洗步骤编号（步骤级修正时使用）',
    ADD COLUMN correction_scope VARCHAR(16) NULL DEFAULT 'VALUE'
        COMMENT '修正范围：VALUE（字段级） | STEP（步骤级） | PATTERN（模式级）',
    ADD COLUMN operation_type VARCHAR(16) NULL
        COMMENT '步骤级操作类型：REWRITE（改写） | DROP（删除） | MERGE（合并）',
    ADD COLUMN candidate_step_text TEXT NULL
        COMMENT '候选步骤文案（步骤级修正时使用）',
    ADD COLUMN confirmed_step_text TEXT NULL
        COMMENT '确认后的步骤文案（步骤级修正时使用）',
    ADD COLUMN related_step_no INT NULL
        COMMENT '关联步骤编号（合并步骤时使用）';

-- 2. 模式学习辅助表：已确认修正沉淀为轻量模式
CREATE TABLE trace_correction_pattern (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,

    -- 模式匹配条件
    page_url_pattern        VARCHAR(512) NULL
        COMMENT '页面 URL 匹配模式（支持前缀匹配或通配符）',
    page_title_keyword      VARCHAR(128) NULL
        COMMENT '页面标题关键词',
    element_text_pattern    VARCHAR(256) NULL
        COMMENT '元素文本匹配模式',
    element_role            VARCHAR(64) NULL
        COMMENT '元素 role 类型',
    dialog_title_keyword    VARCHAR(128) NULL
        COMMENT '弹窗标题关键词',
    section_title_keyword   VARCHAR(128) NULL
        COMMENT '区块标题关键词',

    -- 模式动作
    correction_type         VARCHAR(32) NOT NULL
        COMMENT 'OBJECT_LABEL | CHECKBOX_SEMANTICS | DIALOG_ACTION | INPUT_FINAL_VALUE | BUSINESS_ACTION_MAPPING | STEP_TEXT_OVERRIDE | STEP_NOISE_DECISION | STEP_MERGE_SUGGESTION',
    operation_type          VARCHAR(16) NULL
        COMMENT 'REWRITE | DROP | MERGE',
    from_text               TEXT NOT NULL
        COMMENT '原始文本模式',
    to_text                 TEXT NOT NULL
        COMMENT '修正后的目标文本',

    -- 统计与质量
    confirmed_count         INT NOT NULL DEFAULT 1
        COMMENT '该模式被确认的次数（用于评估置信度）',
    last_confirmed_at       DATETIME NOT NULL
        COMMENT '最近一次确认时间',

    -- 溯源
    source_correction_ids   VARCHAR(512) NOT NULL
        COMMENT '来源修正建议 ID 列表（逗号分隔）',

    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='轨迹修正轻量模式记忆。基于已确认修正结果沉淀，用于提升后续候选建议质量。';

-- 3. 索引
CREATE INDEX idx_tcc_step_scope ON trace_correction_candidate (step_no, correction_scope);
CREATE INDEX idx_tcc_operation   ON trace_correction_candidate (operation_type);
CREATE INDEX idx_tcp_project     ON trace_correction_pattern (project_id);
CREATE INDEX idx_tcp_match       ON trace_correction_pattern (project_id, page_title_keyword, element_role);
CREATE INDEX idx_tcp_type        ON trace_correction_pattern (correction_type);
