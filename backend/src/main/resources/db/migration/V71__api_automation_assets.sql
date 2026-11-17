-- Keep existing UI assets and their history; allow a separate API mapping for the same case.
ALTER TABLE automation_asset
    ADD COLUMN asset_type VARCHAR(8) NOT NULL DEFAULT 'UI' AFTER case_id,
    DROP INDEX uk_automation_case,
    ADD UNIQUE KEY uk_automation_case_type (project_id, case_kind, case_id, asset_type);

CREATE TABLE automation_api_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    result VARCHAR(32) NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_api_evidence_request (asset_id, request_key),
    INDEX idx_api_evidence_asset_version (asset_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE automation_run
    MODIFY evidence_session_id BIGINT NULL,
    ADD COLUMN api_evidence_id BIGINT NULL AFTER evidence_session_id,
    ADD UNIQUE KEY uk_automation_run_api_evidence (api_evidence_id);
