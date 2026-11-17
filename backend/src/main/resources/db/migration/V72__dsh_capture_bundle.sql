CREATE TABLE dsh_capture_bundle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    summary_json TEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_dsh_capture_bundle_session (session_id),
    UNIQUE KEY uk_dsh_capture_bundle_request (project_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
