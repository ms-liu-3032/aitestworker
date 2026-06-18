-- V28: 开源运行态默认值收口
-- 不改写已发布的历史迁移，统一在新迁移中把业务域默认值和注释调整为通用平台口径。

UPDATE manual_import_task
SET business_domain = ''
WHERE business_domain = CONVERT(0xE699BAE883BDE8AEBFE5AEA2 USING utf8mb4) COLLATE utf8mb4_unicode_ci;

ALTER TABLE manual_import_task
    MODIFY COLUMN business_domain VARCHAR(64) NOT NULL DEFAULT ''
        COMMENT '业务域，如：审批流、CRM、设备巡检';

ALTER TABLE test_object_model
    MODIFY COLUMN business_domain VARCHAR(64) NULL
        COMMENT '业务域，如：审批流、CRM、设备巡检';
