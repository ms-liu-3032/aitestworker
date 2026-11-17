-- A test case owns one automation mapping. Versions and runs are immutable evidence.
CREATE TABLE automation_asset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    case_kind VARCHAR(16) NOT NULL,
    case_id BIGINT NOT NULL,
    source_work_session_id BIGINT NOT NULL,
    scope VARCHAR(16) NOT NULL DEFAULT 'PROJECT',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version INT NOT NULL DEFAULT 1,
    heal_count INT NOT NULL DEFAULT 0,
    blocked_reason VARCHAR(512) NULL,
    request_key VARCHAR(128) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_automation_case (project_id, case_kind, case_id),
    UNIQUE KEY uk_automation_create_request (project_id, created_by, request_key),
    INDEX idx_automation_project_status (project_id, status),
    INDEX idx_automation_owner (created_by, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_asset_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    mapping_json MEDIUMTEXT NOT NULL,
    mapping_hash CHAR(64) NOT NULL,
    change_reason VARCHAR(512) NOT NULL,
    revision_kind VARCHAR(16) NOT NULL DEFAULT 'INITIAL',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    source_run_id BIGINT NULL,
    verified_run_id BIGINT NULL,
    request_key VARCHAR(128) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    verified_at DATETIME NULL,
    UNIQUE KEY uk_automation_version (asset_id, version_no),
    UNIQUE KEY uk_automation_version_request (asset_id, request_key),
    INDEX idx_automation_version_status (asset_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    result VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL,
    evidence_session_id BIGINT NOT NULL,
    failure_step_index INT NULL,
    failure_reason VARCHAR(2000) NULL,
    request_key VARCHAR(128) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_automation_run_request (asset_id, request_key),
    UNIQUE KEY uk_automation_run_evidence (evidence_session_id),
    INDEX idx_automation_run_asset (asset_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_heal_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    from_version INT NOT NULL,
    to_version INT NOT NULL,
    failure_run_id BIGINT NOT NULL,
    diagnosis VARCHAR(2000) NOT NULL,
    diff_json MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    resolved_at DATETIME NULL,
    UNIQUE KEY uk_automation_heal_target (asset_id, to_version),
    INDEX idx_automation_heal_source (asset_id, failure_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A reusable publication freezes a verified version; later project heals do not change it.
CREATE TABLE automation_publication (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    scope VARCHAR(16) NOT NULL DEFAULT 'REUSABLE',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    published_by BIGINT NOT NULL,
    published_at DATETIME NOT NULL,
    UNIQUE KEY uk_automation_publication_version (asset_id, version_no, scope),
    INDEX idx_automation_publication_scope (scope, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- DSH edits one owned draft in place, retaining each revision and an optimistic version.
CREATE TABLE dsh_draft_revision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    draft_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at DATETIME NOT NULL,
    UNIQUE KEY uk_dsh_draft_revision_version (draft_id, version_no),
    UNIQUE KEY uk_dsh_draft_revision_request (draft_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
