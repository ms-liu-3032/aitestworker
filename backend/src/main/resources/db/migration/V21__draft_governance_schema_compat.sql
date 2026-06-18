-- ============================================================
-- V21: draft governance schema compatibility
-- 说明：
-- 1. 部分环境在 V7 发布前已执行过旧版本同名迁移，Flyway 不会重放，
--    导致 test_case_draft / test_point_draft 缺少治理字段。
-- 2. 这里补一条幂等兼容迁移，保证旧库重启后也能自愈到当前代码所需结构。
-- ============================================================

DELIMITER $$

CREATE PROCEDURE sp_add_column_if_missing_v21(
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

CREATE PROCEDURE sp_create_index_if_missing_v21(
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

CALL sp_add_column_if_missing_v21(
    'test_case_draft',
    'case_scope',
    'ALTER TABLE test_case_draft ADD COLUMN case_scope VARCHAR(32) NOT NULL DEFAULT ''PERSONAL'' AFTER asset_status'
);
CALL sp_add_column_if_missing_v21(
    'test_case_draft',
    'case_status',
    'ALTER TABLE test_case_draft ADD COLUMN case_status VARCHAR(32) NOT NULL DEFAULT ''DRAFT'' AFTER case_scope'
);
CALL sp_add_column_if_missing_v21(
    'test_case_draft',
    'created_by',
    'ALTER TABLE test_case_draft ADD COLUMN created_by BIGINT NOT NULL DEFAULT 0 AFTER case_status'
);

CALL sp_add_column_if_missing_v21(
    'test_point_draft',
    'case_scope',
    'ALTER TABLE test_point_draft ADD COLUMN case_scope VARCHAR(32) NOT NULL DEFAULT ''PERSONAL'' AFTER status'
);
CALL sp_add_column_if_missing_v21(
    'test_point_draft',
    'created_by',
    'ALTER TABLE test_point_draft ADD COLUMN created_by BIGINT NOT NULL DEFAULT 0 AFTER case_scope'
);

CALL sp_create_index_if_missing_v21(
    'test_case_draft',
    'idx_tcd_owner',
    'CREATE INDEX idx_tcd_owner ON test_case_draft(created_by)'
);
CALL sp_create_index_if_missing_v21(
    'test_case_draft',
    'idx_tcd_created',
    'CREATE INDEX idx_tcd_created ON test_case_draft(created_by, created_at)'
);
CALL sp_create_index_if_missing_v21(
    'test_point_draft',
    'idx_tpd_owner',
    'CREATE INDEX idx_tpd_owner ON test_point_draft(created_by)'
);
CALL sp_create_index_if_missing_v21(
    'test_point_draft',
    'idx_tpd_created',
    'CREATE INDEX idx_tpd_created ON test_point_draft(created_by, created_at)'
);

UPDATE test_case_draft d
LEFT JOIN generation_session gs ON gs.id = d.session_id
LEFT JOIN generation_task gt ON gt.id = d.task_id
SET d.case_scope = COALESCE(NULLIF(d.case_scope, ''), 'PERSONAL'),
    d.case_status = COALESCE(NULLIF(d.case_status, ''), NULLIF(d.asset_status, ''), 'DRAFT'),
    d.created_by = CASE
        WHEN d.created_by IS NOT NULL AND d.created_by <> 0 THEN d.created_by
        WHEN gs.created_by IS NOT NULL AND gs.created_by <> 0 THEN gs.created_by
        WHEN gt.created_by IS NOT NULL AND gt.created_by <> 0 THEN gt.created_by
        ELSE d.created_by
    END
WHERE d.case_scope IS NULL
   OR d.case_scope = ''
   OR d.case_status IS NULL
   OR d.case_status = ''
   OR d.created_by IS NULL
   OR d.created_by = 0;

UPDATE test_point_draft d
LEFT JOIN generation_session gs ON gs.id = d.session_id
LEFT JOIN generation_task gt ON gt.id = d.task_id
SET d.case_scope = COALESCE(NULLIF(d.case_scope, ''), 'PERSONAL'),
    d.created_by = CASE
        WHEN d.created_by IS NOT NULL AND d.created_by <> 0 THEN d.created_by
        WHEN gs.created_by IS NOT NULL AND gs.created_by <> 0 THEN gs.created_by
        WHEN gt.created_by IS NOT NULL AND gt.created_by <> 0 THEN gt.created_by
        ELSE d.created_by
    END
WHERE d.case_scope IS NULL
   OR d.case_scope = ''
   OR d.created_by IS NULL
   OR d.created_by = 0;

DROP PROCEDURE IF EXISTS sp_add_column_if_missing_v21;
DROP PROCEDURE IF EXISTS sp_create_index_if_missing_v21;
