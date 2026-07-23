ALTER TABLE test_case_draft
    ADD COLUMN scenario_type VARCHAR(32) NOT NULL DEFAULT 'POSITIVE' AFTER case_type;

ALTER TABLE test_case_asset
    ADD COLUMN scenario_type VARCHAR(32) NOT NULL DEFAULT 'POSITIVE' AFTER case_type;
