ALTER TABLE dsh_capture_run
    ADD COLUMN work_session_id BIGINT NULL AFTER profile_id,
    ADD INDEX idx_dsh_capture_work (work_session_id, session_id);
