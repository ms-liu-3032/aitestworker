-- V26: business_pack 绑定关系表 + 生命周期增强
-- 让 business_pack 与平台能力的关系可查询、可追溯

-- 1. 业务包规则绑定（business_pack <-> trace rulepack）
CREATE TABLE business_pack_rule_binding (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    rule_type       VARCHAR(32) NOT NULL COMMENT 'TRACE_RULE | CLEANING_PATTERN | DESCRIPTION_RULE',
    rule_ref        VARCHAR(256) NOT NULL COMMENT '规则标识（如 JSON pack name 或 rule key）',
    rule_config_json JSON NULL COMMENT '规则配置快照',
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 0.50,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp_rule_binding (pack_id, rule_type, rule_ref(191)),
    INDEX idx_bp_rule_project (project_id, rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包规则绑定：将业务包与轨迹清洗规则、描述规则等关联';

-- 2. 业务包扫描绑定（business_pack <-> page scan profile）
CREATE TABLE business_pack_scan_binding (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    scan_profile_id BIGINT NOT NULL COMMENT 'page_scan_profile.id',
    route_path      VARCHAR(512) NULL,
    page_label      VARCHAR(256) NULL,
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 0.50,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp_scan_binding (pack_id, scan_profile_id),
    INDEX idx_bp_scan_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包扫描绑定：将业务包与页面画像关联';

-- 3. 业务包 TOM 绑定（business_pack <-> test_object_model）
CREATE TABLE business_pack_tom_binding (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    tom_id          BIGINT NOT NULL COMMENT 'test_object_model.id',
    tom_name        VARCHAR(256) NULL,
    tom_type        VARCHAR(32) NULL,
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 0.50,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp_tom_binding (pack_id, tom_id),
    INDEX idx_bp_tom_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包 TOM 绑定：将业务包与测试对象模型关联';

-- 4. 业务包语义绑定（business_pack <-> semantic_pack）
CREATE TABLE business_pack_semantic_binding (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    semantic_pack_id BIGINT NOT NULL COMMENT 'semantic_pack.id',
    signal_category VARCHAR(64) NULL,
    signal_title    VARCHAR(512) NULL,
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 0.50,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bp_sem_binding (pack_id, semantic_pack_id),
    INDEX idx_bp_sem_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包语义绑定：将业务包与语义信号关联';

-- 5. 业务包消费记录（追踪哪些链路消费了业务包）
CREATE TABLE business_pack_consumption_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id         BIGINT NOT NULL,
    project_id      BIGINT NOT NULL,
    consumer_type   VARCHAR(32) NOT NULL COMMENT 'TRACE_CLEAN | SCOPE_ANALYSIS | CASE_GENERATION | SUMMARY | SEMANTIC_CONTEXT',
    consumer_ref    VARCHAR(256) NULL COMMENT '消费者引用（如 sessionId / taskId）',
    signal_count    INT NOT NULL DEFAULT 0 COMMENT '消费的信号数量',
    consumed_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bp_consumption_project (project_id, consumer_type),
    INDEX idx_bp_consumption_pack (pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包消费记录：追踪业务包被哪些链路消费';
