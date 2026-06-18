-- V29: 轨迹规则包配置化
-- 默认运行态不内置业务样例；全局 ACTIVE 规则包可从数据库加载，classpath JSON 保留为兼容模式。

CREATE TABLE trace_rule_pack_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT NULL COMMENT 'NULL 表示全局规则包，非 NULL 表示项目级规则包',
    pack_key        VARCHAR(128) NOT NULL COMMENT '规则包标识',
    pack_name       VARCHAR(256) NOT NULL COMMENT '规则包名称',
    pack_type       VARCHAR(32) NOT NULL DEFAULT 'TRACE_CLEANING' COMMENT 'TRACE_CLEANING | WORKFLOW | DESCRIPTION',
    version         INT NOT NULL DEFAULT 1,
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | ACTIVE | INACTIVE | ARCHIVED',
    priority        INT NOT NULL DEFAULT 0 COMMENT '加载优先级，数值越大越靠前',
    config_json     JSON NOT NULL COMMENT 'TraceRuleSetConfig JSON',
    description     VARCHAR(512) NULL,
    created_by      BIGINT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trp_project_key (project_id, pack_key),
    INDEX idx_trp_global_active (project_id, status, priority),
    INDEX idx_trp_project_status (project_id, status),
    INDEX idx_trp_key (pack_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='轨迹规则包配置：支持数据库化规则包加载和后续项目级扩展';
