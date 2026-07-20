-- V39: LLM runtime task governance
-- Adds async run state and idempotency fields without changing existing
-- generation_task.status/current_stage workflow semantics.

DELIMITER $$

CREATE PROCEDURE sp_add_column_if_missing_v39(
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

CREATE PROCEDURE sp_create_index_if_missing_v39(
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

CALL sp_add_column_if_missing_v39(
    'generation_task',
    'run_status',
    'ALTER TABLE generation_task ADD COLUMN run_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' AFTER status'
);

CALL sp_add_column_if_missing_v39(
    'generation_task',
    'request_hash',
    'ALTER TABLE generation_task ADD COLUMN request_hash VARCHAR(128) NULL AFTER run_status'
);

CALL sp_add_column_if_missing_v39(
    'generation_task',
    'error_code',
    'ALTER TABLE generation_task ADD COLUMN error_code VARCHAR(64) NULL AFTER request_hash'
);

CALL sp_add_column_if_missing_v39(
    'generation_task',
    'error_message',
    'ALTER TABLE generation_task ADD COLUMN error_message TEXT NULL AFTER error_code'
);

CALL sp_add_column_if_missing_v39(
    'generation_task',
    'retry_count',
    'ALTER TABLE generation_task ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER error_message'
);

CALL sp_add_column_if_missing_v39(
    'generation_task',
    'started_at',
    'ALTER TABLE generation_task ADD COLUMN started_at DATETIME NULL AFTER retry_count'
);

CALL sp_add_column_if_missing_v39(
    'generation_task',
    'finished_at',
    'ALTER TABLE generation_task ADD COLUMN finished_at DATETIME NULL AFTER started_at'
);

CALL sp_create_index_if_missing_v39(
    'generation_task',
    'idx_generation_task_hash_status',
    'CREATE INDEX idx_generation_task_hash_status ON generation_task(project_id, task_type, request_hash, run_status)'
);

CALL sp_add_column_if_missing_v39(
    'llm_invocation_log',
    'provider',
    'ALTER TABLE llm_invocation_log ADD COLUMN provider VARCHAR(64) NULL AFTER model_config_id'
);

CALL sp_add_column_if_missing_v39(
    'llm_invocation_log',
    'model_name',
    'ALTER TABLE llm_invocation_log ADD COLUMN model_name VARCHAR(128) NULL AFTER provider'
);

CALL sp_add_column_if_missing_v39(
    'llm_invocation_log',
    'retry_index',
    'ALTER TABLE llm_invocation_log ADD COLUMN retry_index INT NULL AFTER model_name'
);

CALL sp_add_column_if_missing_v39(
    'llm_invocation_log',
    'raw_output_snapshot',
    'ALTER TABLE llm_invocation_log ADD COLUMN raw_output_snapshot TEXT NULL AFTER output_snapshot_ref'
);

DROP PROCEDURE IF EXISTS sp_add_column_if_missing_v39;
DROP PROCEDURE IF EXISTS sp_create_index_if_missing_v39;
