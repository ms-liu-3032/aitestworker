-- V33: test_case_draft 新增分析子版本追踪字段 + affected_cases 输出字段

ALTER TABLE test_case_draft ADD COLUMN analysis_sub_version INT;

ALTER TABLE requirement_analysis ADD COLUMN affected_cases TEXT;
ALTER TABLE requirement_analysis ADD COLUMN change_scope VARCHAR(16);
