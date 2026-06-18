CREATE TABLE worker_bind_code (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME,
    device_id BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_worker_bind_code_hash (code_hash),
    INDEX idx_worker_bind_code_user (user_id),
    INDEX idx_worker_bind_code_status_expires (status, expires_at)
);
