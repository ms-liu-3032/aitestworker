ALTER TABLE automation_asset
    ADD COLUMN source_case_version INT NOT NULL DEFAULT 1 AFTER source_work_session_id;
