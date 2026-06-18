-- V24: business_pack 正式建表
-- 支持业务包的完整生命周期：生成 → 审核 → 激活 → 停用 → 归档

-- 1. 业务包主表
CREATE TABLE business_pack (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    pack_name       VARCHAR(128) NOT NULL COMMENT '业务包名称',
    pack_type       VARCHAR(32) NOT NULL DEFAULT 'AUTO_GENERATED' COMMENT 'AUTO_GENERATED | MANUAL | IMPORTED',
    business_domain VARCHAR(64) NULL COMMENT '业务域标识',
    version         INT NOT NULL DEFAULT 1 COMMENT '版本号，每次更新自增',
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | ACTIVE | INACTIVE | ARCHIVED',
    description     TEXT NULL COMMENT '业务包描述',
    source_types    JSON NULL COMMENT '来源类型列表，如 ["TOM","PAGE_SCAN","TRACE_SUMMARY"]',
    item_count      INT NOT NULL DEFAULT 0 COMMENT '条目数',
    confidence_avg  DECIMAL(3,2) NULL COMMENT '平均置信度',
    built_at        DATETIME NULL COMMENT '自动生成时间',
    activated_at    DATETIME NULL COMMENT '激活时间',
    created_by      BIGINT NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_bp_project (project_id),
    INDEX idx_bp_status (project_id, status),
    INDEX idx_bp_domain (project_id, business_domain),
    INDEX idx_bp_active (project_id, status, business_domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包：从项目资产自动沉淀或手工创建的业务能力集合';

-- 2. 业务包条目表
CREATE TABLE business_pack_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    item_type       VARCHAR(32) NOT NULL COMMENT 'TOM | PAGE | FIELD | ACTION | FLOW | STATE | ASSERTION | TERM | RULE',
    item_key        VARCHAR(256) NOT NULL COMMENT '条目标识（如 TOM name 或 page route_path）',
    item_value      TEXT NULL COMMENT '条目内容/描述',
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 0.50 COMMENT '置信度',
    source_type     VARCHAR(32) NULL COMMENT '来源类型',
    source_ref_id   BIGINT NULL COMMENT '来源引用 ID',
    source_ref_json JSON NULL COMMENT '来源引用详情',
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp_item_pack_key (pack_id, item_type, item_key(191)),
    INDEX idx_bp_item_project (project_id, item_type),
    INDEX idx_bp_item_pack (pack_id, item_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包条目：业务包中的具体能力项';
