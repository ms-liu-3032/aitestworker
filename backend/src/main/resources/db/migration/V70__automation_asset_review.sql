-- Review is separate from execution verification. Keep discarded assets and versions for audit.
ALTER TABLE automation_asset
    ADD COLUMN archived_by BIGINT NULL,
    ADD COLUMN archived_at DATETIME NULL,
    ADD COLUMN archive_reason VARCHAR(512) NULL;

ALTER TABLE automation_asset_version
    ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN reviewed_by BIGINT NULL,
    ADD COLUMN reviewed_at DATETIME NULL,
    ADD COLUMN review_reason VARCHAR(512) NULL;
