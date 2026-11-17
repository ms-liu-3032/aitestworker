CREATE TABLE dsh_work_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    requirement_text LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_dsh_work_task (task_id),
    INDEX idx_dsh_work_owner (user_id, project_id, id)
);

CREATE TABLE dsh_stage_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_session_id BIGINT NOT NULL,
    stage_key VARCHAR(40) NOT NULL,
    version_no INT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    confirmed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_dsh_stage_version (work_session_id, stage_key, version_no),
    INDEX idx_dsh_stage_latest (work_session_id, stage_key, id)
);

ALTER TABLE dsh_draft_request ADD COLUMN work_session_id BIGINT NULL AFTER project_id;
