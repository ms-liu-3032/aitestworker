CREATE TABLE automation_adoption (
    target_asset_id BIGINT PRIMARY KEY,
    source_publication_id BIGINT NOT NULL,
    source_asset_id BIGINT NOT NULL,
    source_version_no INT NOT NULL,
    adopted_by BIGINT NOT NULL,
    adopted_at DATETIME NOT NULL,
    INDEX idx_automation_adoption_publication (source_publication_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE automation_publication
    ADD COLUMN reviewed_by BIGINT NULL,
    ADD COLUMN reviewed_at DATETIME NULL,
    ADD COLUMN retired_by BIGINT NULL,
    ADD COLUMN retired_at DATETIME NULL,
    ADD COLUMN retirement_reason VARCHAR(512) NULL,
    ADD COLUMN superseded_by BIGINT NULL;
CREATE INDEX idx_automation_publication_asset_scope_status
    ON automation_publication(asset_id, scope, status);
UPDATE automation_publication p
JOIN (SELECT asset_id, scope, MAX(id) newest_id FROM automation_publication
      WHERE status = 'ACTIVE' GROUP BY asset_id, scope) latest
  ON latest.asset_id = p.asset_id AND latest.scope = p.scope
SET p.status = 'SUPERSEDED', p.retired_at = NOW(),
    p.retirement_reason = '既有较新发布版已存在', p.superseded_by = latest.newest_id
WHERE p.status = 'ACTIVE' AND p.id <> latest.newest_id;
