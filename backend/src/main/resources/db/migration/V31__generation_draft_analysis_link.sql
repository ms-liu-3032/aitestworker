ALTER TABLE test_case_draft
    ADD COLUMN analysis_id BIGINT NULL AFTER session_id,
    ADD COLUMN analysis_version INT NULL AFTER analysis_id;

CREATE INDEX idx_tcd_analysis ON test_case_draft(analysis_id);
CREATE INDEX idx_tcd_session_analysis ON test_case_draft(session_id, analysis_version);
