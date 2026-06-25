-- V33: test_case_draft 新增增量分析追踪字段 + requirement_analysis 变更范围字段

ALTER TABLE test_case_draft ADD COLUMN analysis_sub_version INT AFTER analysis_version;

ALTER TABLE requirement_analysis ADD COLUMN affected_cases TEXT;
ALTER TABLE requirement_analysis ADD COLUMN change_scope VARCHAR(16);
