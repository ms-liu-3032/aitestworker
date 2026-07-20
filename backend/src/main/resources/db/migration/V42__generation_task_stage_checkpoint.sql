-- Durable analysis/generation node checkpoints.
-- A retried async task reuses completed LLM node outputs and only invokes the failed node again.

CREATE TABLE IF NOT EXISTS generation_task_stage_checkpoint (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    stage_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload LONGTEXT NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    completed_at DATETIME NULL,
    UNIQUE KEY uk_generation_task_stage_checkpoint (task_id, stage_key),
    KEY idx_generation_task_stage_checkpoint_task (task_id, status)
);
