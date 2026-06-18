-- V27: 扫描源配置化
-- 将 BuiltinScanSourceProvider 从 Java 接口演进为数据库配置

CREATE TABLE scan_source_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT NULL COMMENT 'NULL 表示全局配置，非 NULL 表示项目级配置',
    source_key      VARCHAR(64) NOT NULL COMMENT '扫描源标识',
    source_label    VARCHAR(128) NOT NULL COMMENT '扫描源显示名称',
    source_type     VARCHAR(32) NOT NULL DEFAULT 'URL_LIST' COMMENT 'URL_LIST | BUILTIN_JSON | PROJECT_ASSET',
    source_url      VARCHAR(1024) NULL COMMENT 'URL 扫描源的链接',
    source_file_path VARCHAR(512) NULL COMMENT 'JSON 文件路径（兼容旧模式）',
    default_selected TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认选中',
    enabled         TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    description     VARCHAR(512) NULL COMMENT '描述',
    config_json     JSON NULL COMMENT '扩展配置',
    created_by      BIGINT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scan_source_project_key (project_id, source_key),
    INDEX idx_scan_source_global (project_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='扫描源配置：支持全局和项目级扫描源，不再依赖 Java 接口';
