-- Generation workflow identifiers grew as staged analysis, scope review and retries were added.
-- Keep control fields bounded and indexable, but reserve enough capacity for future platform stages.
-- Business/model prose remains in JSON/TEXT/LONGTEXT columns and must not be stored in these fields.

ALTER TABLE requirement_analysis
    MODIFY COLUMN status VARCHAR(128) NOT NULL DEFAULT 'ANALYZING',
    MODIFY COLUMN change_scope VARCHAR(64) NULL;

ALTER TABLE generation_session
    MODIFY COLUMN status VARCHAR(64) NOT NULL DEFAULT 'ACTIVE',
    MODIFY COLUMN current_stage VARCHAR(128) NOT NULL DEFAULT 'REQUIREMENT_INPUT',
    MODIFY COLUMN tom_mode VARCHAR(64) NOT NULL DEFAULT 'DIRECT';

ALTER TABLE generation_message
    MODIFY COLUMN role VARCHAR(32) NOT NULL,
    MODIFY COLUMN stage VARCHAR(128) NULL;

ALTER TABLE generation_task
    MODIFY COLUMN requirement_type VARCHAR(128) NULL,
    MODIFY COLUMN current_stage VARCHAR(160) NOT NULL DEFAULT 'CREATED',
    MODIFY COLUMN status VARCHAR(128) NOT NULL DEFAULT 'CREATED',
    MODIFY COLUMN run_status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    MODIFY COLUMN error_code VARCHAR(128) NULL,
    MODIFY COLUMN generation_mode VARCHAR(128) NOT NULL DEFAULT 'DIRECT';

ALTER TABLE generation_question
    MODIFY COLUMN stage VARCHAR(128) NOT NULL,
    MODIFY COLUMN question_source VARCHAR(128) NULL,
    MODIFY COLUMN question_level VARCHAR(64) NULL,
    MODIFY COLUMN answer_status VARCHAR(64) NOT NULL DEFAULT 'PENDING';

ALTER TABLE prompt_snapshot
    MODIFY COLUMN stage VARCHAR(128) NOT NULL;

ALTER TABLE context_manifest
    MODIFY COLUMN stage VARCHAR(128) NOT NULL;

ALTER TABLE llm_invocation_log
    MODIFY COLUMN stage VARCHAR(128) NOT NULL,
    MODIFY COLUMN status VARCHAR(64) NOT NULL,
    MODIFY COLUMN error_code VARCHAR(128) NULL;

ALTER TABLE security_event_log
    MODIFY COLUMN event_type VARCHAR(128) NOT NULL,
    MODIFY COLUMN severity VARCHAR(32) NOT NULL;

ALTER TABLE generation_task_stage_checkpoint
    MODIFY COLUMN status VARCHAR(64) NOT NULL,
    MODIFY COLUMN error_code VARCHAR(128) NULL;

ALTER TABLE skill_execution_log
    MODIFY COLUMN stage VARCHAR(128) NOT NULL,
    MODIFY COLUMN status VARCHAR(64) NOT NULL,
    MODIFY COLUMN error_code VARCHAR(128) NULL;

ALTER TABLE generation_attachment
    MODIFY COLUMN parse_status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    MODIFY COLUMN knowledge_deposition_status VARCHAR(64) NOT NULL DEFAULT 'PENDING';
