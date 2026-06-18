-- TOM 分层：PROJECT / SYSTEM scope
ALTER TABLE test_object_model
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'PROJECT' AFTER project_id,
    ADD COLUMN source_project_id BIGINT NULL AFTER scope,
    ADD COLUMN source_project_tom_id BIGINT NULL AFTER source_project_id,
    ADD COLUMN upgraded_by BIGINT NULL AFTER rejected_reason,
    ADD COLUMN upgraded_at DATETIME NULL AFTER upgraded_by;

CREATE INDEX idx_tom_scope_status ON test_object_model(scope, status);

-- 生成任务扩展：反问 + TOM 融合快照
ALTER TABLE generation_task
    ADD COLUMN project_tom_snapshot LONGTEXT NULL AFTER tom_hit_count,
    ADD COLUMN system_tom_snapshot LONGTEXT NULL AFTER project_tom_snapshot,
    ADD COLUMN tom_weight_config VARCHAR(64) NULL DEFAULT '0.6/0.4' AFTER system_tom_snapshot,
    ADD COLUMN clarification_questions_snapshot LONGTEXT NULL AFTER tom_weight_config,
    ADD COLUMN clarification_answers_snapshot LONGTEXT NULL AFTER clarification_questions_snapshot,
    ADD COLUMN assumptions_snapshot LONGTEXT NULL AFTER clarification_answers_snapshot;
