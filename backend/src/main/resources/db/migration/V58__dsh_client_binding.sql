-- DSH clients use their own revocable credentials. Worker tokens remain worker-only.
CREATE TABLE dsh_bind_code (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    code_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME NULL,
    connection_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_dsh_bind_code_hash (code_hash),
    INDEX idx_dsh_bind_code_user (user_id),
    INDEX idx_dsh_bind_code_expiry (status, expires_at)
);

CREATE TABLE dsh_connection (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    user_token_hash CHAR(64) NOT NULL,
    agent_token_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_used_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    UNIQUE KEY uk_dsh_connection_user_token (user_token_hash),
    UNIQUE KEY uk_dsh_connection_agent_token (agent_token_hash),
    INDEX idx_dsh_connection_user (user_id, status)
);

CREATE TABLE dsh_draft_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    draft_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_dsh_draft_request (user_id, project_id, request_key)
);
