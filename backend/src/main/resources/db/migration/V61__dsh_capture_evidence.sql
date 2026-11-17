CREATE TABLE dsh_capture_run (
    session_id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RECORDING',
    created_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    INDEX idx_dsh_capture_owner (user_id, project_id, session_id)
);

CREATE TABLE dsh_evidence_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_dsh_evidence_session (session_id, id)
);
