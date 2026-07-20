-- V40: multi-instance minute quota counter for LLM gateway.
-- Stores only quota keys and counters, not prompts or generated content.

CREATE TABLE IF NOT EXISTS llm_quota_usage_minute (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quota_key VARCHAR(128) NOT NULL,
    window_minute BIGINT NOT NULL,
    count_value INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_lqm_key_window (quota_key, window_minute),
    INDEX idx_lqm_window (window_minute)
);
