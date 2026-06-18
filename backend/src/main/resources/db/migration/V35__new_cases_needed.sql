-- V35: requirement_analysis 新增 new_cases_needed 字段
-- 存储增量分析识别的需要新增的用例列表（JSON）

ALTER TABLE requirement_analysis ADD COLUMN new_cases_needed TEXT;
