-- V25: business_pack 关系表 + 版本管理增强
-- 支持业务包之间的依赖、包含、联动、补充关系

-- 1. 业务包关系表
CREATE TABLE business_pack_relation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    source_pack_id  BIGINT NOT NULL COMMENT '源业务包 ID',
    target_pack_id  BIGINT NOT NULL COMMENT '目标业务包 ID',
    relation_type   VARCHAR(32) NOT NULL COMMENT 'DEPENDS_ON | CONTAINS | LINKED | SUPPLEMENTS',
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 0.50 COMMENT '关系置信度',
    description     VARCHAR(512) NULL COMMENT '关系描述',
    source_type     VARCHAR(32) NULL COMMENT '关系来源：AUTO_INFERRED | MANUAL',
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp_relation_pack (source_pack_id, target_pack_id, relation_type),
    INDEX idx_bp_relation_project (project_id),
    INDEX idx_bp_relation_source (source_pack_id),
    INDEX idx_bp_relation_target (target_pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包关系：描述业务包之间的依赖、包含、联动、补充关系';

-- 2. 业务包快照表（版本化）
CREATE TABLE business_pack_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    snapshot_no     INT NOT NULL COMMENT '快照序号',
    pack_name       VARCHAR(128) NOT NULL,
    business_domain VARCHAR(64) NULL,
    status          VARCHAR(32) NOT NULL,
    item_count      INT NOT NULL DEFAULT 0,
    confidence_avg  DECIMAL(3,2) NULL,
    snapshot_json   JSON NULL COMMENT '完整快照数据',
    change_summary  VARCHAR(512) NULL COMMENT '变更摘要',
    created_by      BIGINT NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp_snapshot_no (pack_id, snapshot_no),
    INDEX idx_bp_snapshot_project (project_id, pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包快照：每次状态变更时记录完整快照，支持版本回溯';
