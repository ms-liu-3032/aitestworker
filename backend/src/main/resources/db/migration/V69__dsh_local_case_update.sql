ALTER TABLE trace_generated_case
    ADD COLUMN version_no INT NOT NULL DEFAULT 1;

CREATE TABLE dsh_local_case_update_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    local_case_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_version INT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_dsh_local_case_update (project_id, user_id, local_case_id, request_key),
    INDEX idx_dsh_local_case_update_id (project_id, local_case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
