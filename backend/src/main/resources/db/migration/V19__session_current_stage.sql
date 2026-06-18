-- V19: Add current_stage to generation_session

ALTER TABLE generation_session
    ADD COLUMN current_stage VARCHAR(64) NOT NULL DEFAULT 'REQUIREMENT_INPUT' AFTER status,
    ADD INDEX idx_gs_stage (project_id, current_stage);
