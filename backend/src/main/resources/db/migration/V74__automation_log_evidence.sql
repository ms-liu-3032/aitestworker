-- Optional versioned log assertion evidence for both UI and API runs. No platform host or credentials.
ALTER TABLE automation_run ADD COLUMN log_evidence_json MEDIUMTEXT NULL;
