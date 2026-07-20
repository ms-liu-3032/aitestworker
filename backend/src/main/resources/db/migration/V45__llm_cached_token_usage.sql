ALTER TABLE llm_invocation_log
    ADD COLUMN token_cached_input INT NOT NULL DEFAULT 0 AFTER token_input;
