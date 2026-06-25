-- V23: 移除 manual_import_task.business_domain 的历史业务默认值
-- 将历史遗留的业务默认值改为通用空值

-- 1. 回填历史数据中仍为历史默认值的记录为空字符串
UPDATE manual_import_task
SET business_domain = ''
WHERE business_domain = 'LEGACY_DEFAULT_DOMAIN';

-- 2. 修改列默认值
ALTER TABLE manual_import_task
    ALTER COLUMN business_domain SET DEFAULT '';
