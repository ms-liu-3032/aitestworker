-- Persist the three user-visible TOM modes instead of collapsing them into a boolean.
ALTER TABLE generation_session
    ADD COLUMN tom_mode VARCHAR(32) NULL AFTER use_mini_tom;

UPDATE generation_session
SET tom_mode = CASE
    WHEN use_mini_tom = 1 THEN 'PROJECT_AND_SYSTEM_TOM'
    ELSE 'DIRECT'
END
WHERE tom_mode IS NULL OR tom_mode = '';

ALTER TABLE generation_session
    MODIFY COLUMN tom_mode VARCHAR(32) NOT NULL DEFAULT 'DIRECT';
