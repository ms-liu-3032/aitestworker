-- V23: 移除 manual_import_task.business_domain 的旧样例默认值
-- 将历史遗留的样例默认值改为通用空值

-- 1. 回填历史数据中仍为旧样例默认值的记录为空字符串
UPDATE manual_import_task
SET business_domain = ''
WHERE business_domain = CONVERT(0xE699BAE883BDE8AEBFE5AEA2 USING utf8mb4) COLLATE utf8mb4_unicode_ci;

-- 2. 修改列默认值
ALTER TABLE manual_import_task
    ALTER COLUMN business_domain SET DEFAULT '';
