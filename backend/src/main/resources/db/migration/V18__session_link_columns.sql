-- V18: Add session_id link columns to existing tables

ALTER TABLE test_case_draft
    ADD COLUMN session_id BIGINT NULL AFTER task_id,
    ADD INDEX idx_tcd_session (session_id);

ALTER TABLE test_point_draft
    ADD COLUMN session_id BIGINT NULL AFTER task_id,
    ADD INDEX idx_tpd_session (session_id);

ALTER TABLE generation_task
    ADD COLUMN session_id BIGINT NULL,
    ADD INDEX idx_gt_session (session_id);
