-- V50: generation_session introduced PROJECT_AND_SYSTEM_TOM, which is longer
-- than the original generation_task.generation_mode VARCHAR(16).

ALTER TABLE generation_task
    MODIFY COLUMN generation_mode VARCHAR(64) NOT NULL DEFAULT 'DIRECT'
        COMMENT 'DIRECT | PROJECT_TOM | PROJECT_AND_SYSTEM_TOM';
