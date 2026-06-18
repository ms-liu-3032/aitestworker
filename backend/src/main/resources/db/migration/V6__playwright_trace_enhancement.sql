ALTER TABLE browser_trace_event
  ADD COLUMN normalized_locator TEXT NULL,
  ADD COLUMN section_title VARCHAR(255) NULL,
  ADD COLUMN dialog_title VARCHAR(255) NULL,
  ADD COLUMN object_label VARCHAR(255) NULL;

ALTER TABLE browser_trace_session
  ADD COLUMN screencast_path VARCHAR(500) NULL,
  ADD COLUMN screencast_started_at_utc DATETIME NULL,
  ADD COLUMN screencast_stopped_at_utc DATETIME NULL,
  ADD COLUMN screencast_duration_ms BIGINT NULL;

ALTER TABLE browser_issue_clip
  ADD COLUMN screencast_path VARCHAR(500) NULL,
  ADD COLUMN screencast_clip_start_ms BIGINT NULL,
  ADD COLUMN screencast_clip_end_ms BIGINT NULL;
