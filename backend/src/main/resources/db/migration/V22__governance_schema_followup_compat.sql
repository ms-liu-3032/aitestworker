-- ============================================================
-- V22: governance schema follow-up compatibility
-- 说明：
-- 1. 部分老环境在 V7 发布前已执行过旧版本同名迁移，导致后续治理字段未落库。
-- 2. V21 已补齐 draft 相关核心链路；这里继续补齐 generation_task /
--    test_case_asset / skill_execution_log 的剩余治理字段，避免深层链路再出现
--    “源码正确但 live schema 缺列”的运行态问题。
-- ============================================================

DELIMITER $$

CREATE PROCEDURE sp_add_column_if_missing_v22(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = p_sql;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE sp_create_index_if_missing_v22(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = p_sql;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL sp_add_column_if_missing_v22(
    'generation_task',
    'task_type',
    'ALTER TABLE generation_task ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT ''GENERATION'' AFTER created_by'
);
CALL sp_add_column_if_missing_v22(
    'generation_task',
    'prompt_template_id',
    'ALTER TABLE generation_task ADD COLUMN prompt_template_id BIGINT NULL AFTER model_config_id'
);
CALL sp_add_column_if_missing_v22(
    'generation_task',
    'prompt_version',
    'ALTER TABLE generation_task ADD COLUMN prompt_version INT NULL AFTER prompt_template_id'
);
CALL sp_add_column_if_missing_v22(
    'generation_task',
    'prompt_snapshot_id',
    'ALTER TABLE generation_task ADD COLUMN prompt_snapshot_id BIGINT NULL AFTER prompt_version'
);

CALL sp_create_index_if_missing_v22(
    'generation_task',
    'idx_gen_task_type',
    'CREATE INDEX idx_gen_task_type ON generation_task(task_type)'
);
CALL sp_create_index_if_missing_v22(
    'generation_task',
    'idx_gen_task_owner',
    'CREATE INDEX idx_gen_task_owner ON generation_task(created_by)'
);

CALL sp_add_column_if_missing_v22(
    'test_case_asset',
    'prompt_snapshot_id',
    'ALTER TABLE test_case_asset ADD COLUMN prompt_snapshot_id BIGINT NULL AFTER source_refs_json'
);
CALL sp_add_column_if_missing_v22(
    'test_case_asset',
    'model_config_id',
    'ALTER TABLE test_case_asset ADD COLUMN model_config_id BIGINT NULL AFTER prompt_snapshot_id'
);
CALL sp_add_column_if_missing_v22(
    'test_case_asset',
    'trust_level',
    'ALTER TABLE test_case_asset ADD COLUMN trust_level VARCHAR(32) NOT NULL DEFAULT ''HISTORICAL_CASE'' AFTER model_config_id'
);
CALL sp_add_column_if_missing_v22(
    'test_case_asset',
    'deprecated_at',
    'ALTER TABLE test_case_asset ADD COLUMN deprecated_at DATETIME NULL AFTER trust_level'
);
CALL sp_add_column_if_missing_v22(
    'test_case_asset',
    'deprecated_by',
    'ALTER TABLE test_case_asset ADD COLUMN deprecated_by BIGINT NULL AFTER deprecated_at'
);
CALL sp_add_column_if_missing_v22(
    'test_case_asset',
    'deprecated_reason',
    'ALTER TABLE test_case_asset ADD COLUMN deprecated_reason TEXT NULL AFTER deprecated_by'
);

CALL sp_create_index_if_missing_v22(
    'test_case_asset',
    'idx_tca_scope_status',
    'CREATE INDEX idx_tca_scope_status ON test_case_asset(case_scope, case_status)'
);
CALL sp_create_index_if_missing_v22(
    'test_case_asset',
    'idx_tca_deprecated',
    'CREATE INDEX idx_tca_deprecated ON test_case_asset(deprecated_at)'
);

CALL sp_add_column_if_missing_v22(
    'skill_execution_log',
    'request_id',
    'ALTER TABLE skill_execution_log ADD COLUMN request_id VARCHAR(64) NULL AFTER stage'
);
CALL sp_add_column_if_missing_v22(
    'skill_execution_log',
    'user_id',
    'ALTER TABLE skill_execution_log ADD COLUMN user_id BIGINT NULL AFTER request_id'
);
CALL sp_add_column_if_missing_v22(
    'skill_execution_log',
    'context_manifest_id',
    'ALTER TABLE skill_execution_log ADD COLUMN context_manifest_id BIGINT NULL AFTER prompt_snapshot'
);
CALL sp_add_column_if_missing_v22(
    'skill_execution_log',
    'skill_template_id',
    'ALTER TABLE skill_execution_log ADD COLUMN skill_template_id BIGINT NULL AFTER context_manifest_id'
);
CALL sp_add_column_if_missing_v22(
    'skill_execution_log',
    'skill_version',
    'ALTER TABLE skill_execution_log ADD COLUMN skill_version INT NULL AFTER skill_template_id'
);

CALL sp_create_index_if_missing_v22(
    'skill_execution_log',
    'idx_sel_request',
    'CREATE INDEX idx_sel_request ON skill_execution_log(request_id)'
);
CALL sp_create_index_if_missing_v22(
    'skill_execution_log',
    'idx_sel_user',
    'CREATE INDEX idx_sel_user ON skill_execution_log(user_id)'
);

UPDATE generation_task
SET task_type = COALESCE(NULLIF(task_type, ''), 'GENERATION')
WHERE task_type IS NULL OR task_type = '';

UPDATE test_case_asset
SET trust_level = COALESCE(NULLIF(trust_level, ''), 'HISTORICAL_CASE')
WHERE trust_level IS NULL OR trust_level = '';

DROP PROCEDURE IF EXISTS sp_add_column_if_missing_v22;
DROP PROCEDURE IF EXISTS sp_create_index_if_missing_v22;
