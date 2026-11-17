-- The open-source baseline intentionally kept Yuque credentials out of V1.
-- Add the optional integration tables after the already-published migrations.
CREATE TABLE IF NOT EXISTS yuque_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    config_name VARCHAR(128) NOT NULL,
    host VARCHAR(255) NOT NULL DEFAULT 'https://www.yuque.com',
    auth_mode VARCHAR(32) NOT NULL,
    username VARCHAR(255),
    password_encrypted TEXT,
    cookie_encrypted TEXT,
    save_credential TINYINT NOT NULL DEFAULT 0,
    repo_scope TEXT,
    exporter_type VARCHAR(64) NOT NULL DEFAULT 'YUQUE_TOOLS',
    enabled TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS yuque_sync_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    yuque_config_id BIGINT NOT NULL,
    sync_mode VARCHAR(32) NOT NULL,
    status VARCHAR(64) NOT NULL,
    export_manifest_ref VARCHAR(512),
    total_doc_count INT NOT NULL DEFAULT 0,
    skipped_doc_count INT NOT NULL DEFAULT 0,
    success_doc_count INT NOT NULL DEFAULT 0,
    failed_doc_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at DATETIME,
    finished_at DATETIME,
    created_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
