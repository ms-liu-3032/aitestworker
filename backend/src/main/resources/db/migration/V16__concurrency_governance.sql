-- ============================================================
-- V16: 并发治理 - version 乐观锁 + 索引优化
-- 说明：
-- 1. 该迁移已经在部分环境里出现过“半执行成功”场景：
--    - 某些 version 列已经落库
--    - 某些索引已经存在
-- 2. MySQL DDL 不是整段事务回滚，因此这里改成基于 information_schema
--    的幂等执行，便于 Flyway repair 后安全重跑。
-- ============================================================

DELIMITER $$

CREATE PROCEDURE sp_add_column_if_missing(
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

CREATE PROCEDURE sp_create_index_if_missing(
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

-- 一、关键表增加 version 字段
CALL sp_add_column_if_missing(
    'test_object_model',
    'version',
    'ALTER TABLE test_object_model ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER updated_at'
);
CALL sp_add_column_if_missing(
    'trace_summary',
    'version',
    'ALTER TABLE trace_summary ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER updated_at'
);
CALL sp_add_column_if_missing(
    'test_case_draft',
    'version',
    'ALTER TABLE test_case_draft ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER updated_at'
);
CALL sp_add_column_if_missing(
    'test_point_draft',
    'version',
    'ALTER TABLE test_point_draft ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER updated_at'
);
CALL sp_add_column_if_missing(
    'test_case_asset',
    'version',
    'ALTER TABLE test_case_asset ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER updated_at'
);

-- 二、test_object_model 索引优化
CALL sp_create_index_if_missing(
    'test_object_model',
    'idx_tom_proj_scope_status',
    'CREATE INDEX idx_tom_proj_scope_status ON test_object_model(project_id, scope, status)'
);
CALL sp_create_index_if_missing(
    'test_object_model',
    'idx_tom_created',
    'CREATE INDEX idx_tom_created ON test_object_model(created_by, created_at)'
);
CALL sp_create_index_if_missing(
    'test_object_model',
    'idx_tom_confirmed',
    'CREATE INDEX idx_tom_confirmed ON test_object_model(confirmed_by, confirmed_at)'
);

-- 三、trace_summary 索引优化
CALL sp_create_index_if_missing(
    'trace_summary',
    'idx_ts_proj_status',
    'CREATE INDEX idx_ts_proj_status ON trace_summary(project_id, status)'
);
CALL sp_create_index_if_missing(
    'trace_summary',
    'idx_ts_proj_group',
    'CREATE INDEX idx_ts_proj_group ON trace_summary(project_id, trace_group_id)'
);
CALL sp_create_index_if_missing(
    'trace_summary',
    'idx_ts_proj_confirmed',
    'CREATE INDEX idx_ts_proj_confirmed ON trace_summary(project_id, confirmed_at)'
);
CALL sp_create_index_if_missing(
    'trace_summary',
    'idx_ts_proj_validity',
    'CREATE INDEX idx_ts_proj_validity ON trace_summary(project_id, validity_label)'
);
CALL sp_create_index_if_missing(
    'trace_summary',
    'idx_ts_created',
    'CREATE INDEX idx_ts_created ON trace_summary(created_by, created_at)'
);

-- 四、browser_trace_event 索引优化
CALL sp_create_index_if_missing(
    'browser_trace_event',
    'idx_bte_session_time',
    'CREATE INDEX idx_bte_session_time ON browser_trace_event(trace_session_id, relative_ms)'
);
CALL sp_create_index_if_missing(
    'browser_trace_event',
    'idx_bte_group_type',
    'CREATE INDEX idx_bte_group_type ON browser_trace_event(trace_group_id, event_type)'
);
CALL sp_create_index_if_missing(
    'browser_trace_event',
    'idx_bte_group_time',
    'CREATE INDEX idx_bte_group_time ON browser_trace_event(trace_group_id, created_at)'
);

-- 五、browser_trace_network 索引优化
CALL sp_create_index_if_missing(
    'browser_trace_network',
    'idx_btn_session_time',
    'CREATE INDEX idx_btn_session_time ON browser_trace_network(trace_session_id, relative_ms)'
);
CALL sp_create_index_if_missing(
    'browser_trace_network',
    'idx_btn_group_status',
    'CREATE INDEX idx_btn_group_status ON browser_trace_network(trace_group_id, status_code)'
);
CALL sp_create_index_if_missing(
    'browser_trace_network',
    'idx_btn_group_method',
    'CREATE INDEX idx_btn_group_method ON browser_trace_network(trace_group_id, method)'
);

-- 六、llm_invocation_log 索引优化
CALL sp_create_index_if_missing(
    'llm_invocation_log',
    'idx_lil_proj_task',
    'CREATE INDEX idx_lil_proj_task ON llm_invocation_log(project_id, task_id)'
);
CALL sp_create_index_if_missing(
    'llm_invocation_log',
    'idx_lil_user_time',
    'CREATE INDEX idx_lil_user_time ON llm_invocation_log(user_id, created_at)'
);
CALL sp_create_index_if_missing(
    'llm_invocation_log',
    'idx_lil_proj_stage_time',
    'CREATE INDEX idx_lil_proj_stage_time ON llm_invocation_log(project_id, stage, created_at)'
);
CALL sp_create_index_if_missing(
    'llm_invocation_log',
    'idx_lil_status_time',
    'CREATE INDEX idx_lil_status_time ON llm_invocation_log(status, created_at)'
);

-- 七、test_case_draft 索引优化
CALL sp_create_index_if_missing(
    'test_case_draft',
    'idx_tcd_proj_task',
    'CREATE INDEX idx_tcd_proj_task ON test_case_draft(project_id, task_id)'
);
CALL sp_create_index_if_missing(
    'test_case_draft',
    'idx_tcd_proj_status',
    'CREATE INDEX idx_tcd_proj_status ON test_case_draft(project_id, asset_status)'
);
CALL sp_create_index_if_missing(
    'test_case_draft',
    'idx_tcd_created',
    'CREATE INDEX idx_tcd_created ON test_case_draft(created_by, created_at)'
);

-- 八、test_point_draft 索引优化
CALL sp_create_index_if_missing(
    'test_point_draft',
    'idx_tpd_proj_task',
    'CREATE INDEX idx_tpd_proj_task ON test_point_draft(project_id, task_id)'
);
CALL sp_create_index_if_missing(
    'test_point_draft',
    'idx_tpd_proj_status',
    'CREATE INDEX idx_tpd_proj_status ON test_point_draft(project_id, status)'
);
CALL sp_create_index_if_missing(
    'test_point_draft',
    'idx_tpd_created',
    'CREATE INDEX idx_tpd_created ON test_point_draft(created_by, created_at)'
);

-- 九、generation_task 索引优化
CALL sp_create_index_if_missing(
    'generation_task',
    'idx_gt_proj_status',
    'CREATE INDEX idx_gt_proj_status ON generation_task(project_id, status)'
);
CALL sp_create_index_if_missing(
    'generation_task',
    'idx_gt_proj_user',
    'CREATE INDEX idx_gt_proj_user ON generation_task(project_id, created_by)'
);
CALL sp_create_index_if_missing(
    'generation_task',
    'idx_gt_created',
    'CREATE INDEX idx_gt_created ON generation_task(created_at)'
);

-- 十、generation_question 索引优化
CALL sp_create_index_if_missing(
    'generation_question',
    'idx_gq_task_status',
    'CREATE INDEX idx_gq_task_status ON generation_question(task_id, answer_status)'
);

DROP PROCEDURE IF EXISTS sp_add_column_if_missing;
DROP PROCEDURE IF EXISTS sp_create_index_if_missing;
