ALTER TABLE automation_asset
    ADD COLUMN promoted_from_draft_id BIGINT NULL,
    ADD COLUMN promoted_by BIGINT NULL,
    ADD COLUMN promoted_at DATETIME NULL;
