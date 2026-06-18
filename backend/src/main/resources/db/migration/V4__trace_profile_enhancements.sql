ALTER TABLE browser_profile
  ADD COLUMN username VARCHAR(255) NULL,
  ADD COLUMN password_cipher VARCHAR(512) NULL;

ALTER TABLE browser_trace_group
  ADD COLUMN profile_id BIGINT NULL,
  ADD INDEX idx_trace_group_profile (profile_id);
