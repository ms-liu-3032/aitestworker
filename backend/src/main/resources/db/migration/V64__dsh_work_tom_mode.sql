ALTER TABLE dsh_work_session
    ADD COLUMN tom_mode VARCHAR(32) NOT NULL DEFAULT 'DIRECT' AFTER requirement_text;
