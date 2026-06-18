-- =====================================================================
-- V10  Trace Correction Candidate — 增加 confirmed_value 字段
--
-- 目标：支持 "系统候选 -> 用户可编辑 -> 确认后应用到当前轨迹"
-- 规则：
--   - candidate_value = 系统猜测值
--   - confirmed_value = 最终采用值（可能等于 candidate_value，也可能是用户改写后的值）
-- =====================================================================

ALTER TABLE trace_correction_candidate
    ADD COLUMN confirmed_value TEXT NULL
        COMMENT '用户确认后的最终采用值（直接确认时等于 candidate_value，手动改写时为用户输入）'
    AFTER candidate_value;
