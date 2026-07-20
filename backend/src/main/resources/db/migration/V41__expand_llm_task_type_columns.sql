-- V41: expand task_type columns for staged LLM task names.
-- Staged requirement analysis uses names such as
-- REQUIREMENT_ANALYSIS_INCREMENTAL_COVERAGE_MATRIX_CONTINUATION,
-- which are longer than the original llm_invocation_log.task_type VARCHAR(32).

ALTER TABLE llm_invocation_log
    MODIFY COLUMN task_type VARCHAR(128) NULL;

ALTER TABLE generation_task
    MODIFY COLUMN task_type VARCHAR(128) NOT NULL DEFAULT 'GENERATION';
