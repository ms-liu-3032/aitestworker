-- V37: Loop 回灌层数据模型

-- 系统功能开关（Loop 总开关 + 子模块开关）
CREATE TABLE system_feature_toggle (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature_key             VARCHAR(64) NOT NULL,
    enabled                 TINYINT(1) NOT NULL DEFAULT 0,
    config_json             TEXT NULL,
    updated_by              BIGINT NULL,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_feature_key (feature_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 学习回灌事件
CREATE TABLE learning_loop_event (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    event_type              VARCHAR(32) NOT NULL,
    source_stage            VARCHAR(32) NULL,
    raw_input               TEXT NULL,
    normalized_issue        TEXT NULL,
    suggested_asset_type    VARCHAR(32) NULL,
    source_refs_json        TEXT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_by              BIGINT NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_loop_event_project (project_id),
    INDEX idx_loop_event_type (event_type),
    INDEX idx_loop_event_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 学习回灌聚类
CREATE TABLE learning_loop_cluster (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    theme                   VARCHAR(255) NULL,
    event_count             INT NOT NULL DEFAULT 0,
    suggested_action        TEXT NULL,
    target_asset_type       VARCHAR(32) NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_loop_cluster_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始化 Loop 总开关（默认关闭）
INSERT INTO system_feature_toggle (feature_key, enabled, config_json, updated_at)
VALUES ('LOOP_ENGINE', 0, '{}', NOW())
ON DUPLICATE KEY UPDATE updated_at = NOW();
