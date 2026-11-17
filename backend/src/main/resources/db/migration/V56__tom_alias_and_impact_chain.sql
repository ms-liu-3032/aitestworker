-- TOM aliases and impact chains.  Both stay reviewable until explicitly confirmed.

CREATE TABLE tom_alias (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    tom_id BIGINT NOT NULL,
    alias_name VARCHAR(256) NOT NULL,
    alias_type VARCHAR(32) NOT NULL DEFAULT 'HISTORICAL_TERM',
    source_type VARCHAR(64) NOT NULL,
    source_ref_id BIGINT NULL,
    source_hash CHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    requires_human_confirm TINYINT(1) NOT NULL DEFAULT 1,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME NULL,
    rejected_by BIGINT NULL,
    rejected_at DATETIME NULL,
    rejected_reason VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_tom_alias_candidate (project_id, tom_id, alias_name, source_hash),
    INDEX idx_tom_alias_project_status (project_id, status),
    INDEX idx_tom_alias_tom (tom_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tom_impact_edge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    from_model_id BIGINT NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_ref VARCHAR(256) NOT NULL,
    impact_type VARCHAR(64) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_ref_id BIGINT NULL,
    source_hash CHAR(64) NULL,
    evidence_json MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    requires_human_confirm TINYINT(1) NOT NULL DEFAULT 1,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME NULL,
    rejected_by BIGINT NULL,
    rejected_at DATETIME NULL,
    rejected_reason VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_tom_impact_candidate (project_id, from_model_id, target_kind, target_ref, impact_type, source_hash),
    INDEX idx_tom_impact_project_status (project_id, status),
    INDEX idx_tom_impact_from (from_model_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
