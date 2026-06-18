-- =====================================================================
-- V12  Mini-TOM — 测试对象模型候选与激活
-- Plan ref: Mini-TOM 最小闭环验证
--
-- 目标：用关系型数据库验证 Mini-TOM 可行性，不依赖 Weaviate / Neo4j
-- =====================================================================

-- 1. 测试对象模型主表
CREATE TABLE test_object_model (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    model_type              VARCHAR(16) NOT NULL
        COMMENT 'MODULE | PAGE | FIELD | ROLE | ACTION | FLOW | STATE | ASSERTION',
    name                    VARCHAR(256) NOT NULL
        COMMENT '对象名称',
    description             TEXT NULL
        COMMENT '对象描述',
    properties_json         JSON NULL
        COMMENT '扩展属性（JSON 格式）',

    -- 来源溯源
    source_type             VARCHAR(32) NOT NULL
        COMMENT 'TRACE_SUMMARY | MANUAL_SECTION | PATTERN',
    source_ref_id           BIGINT NULL
        COMMENT '来源引用 ID（trace_summary.id 或 manual_section.id）',
    source_context          TEXT NULL
        COMMENT '来源上下文摘要（用于人工确认参考）',

    -- 质量与状态
    confidence              DECIMAL(3,2) NOT NULL DEFAULT 0.50
        COMMENT '置信度 0.00-1.00',
    status                  VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE'
        COMMENT 'CANDIDATE | CONFIRMED | REJECTED | ACTIVE | DEPRECATED',
    requires_human_confirm  TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '是否需要人工确认',
    validity_label          VARCHAR(32) NULL
        COMMENT 'HIGH | MEDIUM | LOW | TO_CONFIRM',

    -- 审计
    created_by              BIGINT NOT NULL,
    confirmed_by            BIGINT NULL,
    confirmed_at            DATETIME NULL,
    rejected_by             BIGINT NULL,
    rejected_at             DATETIME NULL,
    rejected_reason         VARCHAR(512) NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='测试对象模型候选层。默认 CANDIDATE，人工确认后变 ACTIVE。';

-- 2. 测试对象模型关系表
CREATE TABLE test_object_model_relation (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    from_model_id           BIGINT NOT NULL
        COMMENT '源对象 ID',
    relation_type           VARCHAR(32) NOT NULL
        COMMENT 'MODULE_HAS_PAGE | PAGE_HAS_FIELD | ROLE_CAN_ACTION | FLOW_HAS_STEP | ACTION_CHANGES_STATE | ACTION_HAS_ASSERTION',
    to_model_id             BIGINT NOT NULL
        COMMENT '目标对象 ID',

    -- 质量与状态
    confidence              DECIMAL(3,2) NOT NULL DEFAULT 0.50
        COMMENT '置信度 0.00-1.00',
    status                  VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE'
        COMMENT 'CANDIDATE | CONFIRMED | REJECTED | ACTIVE',

    -- 来源溯源
    source_type             VARCHAR(32) NOT NULL
        COMMENT 'TRACE_SUMMARY | MANUAL_SECTION | PATTERN',
    source_ref_id           BIGINT NULL
        COMMENT '来源引用 ID',

    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='测试对象模型关系表。连接两个 TOM 实体。';

-- 3. 索引
CREATE INDEX idx_tom_project         ON test_object_model (project_id);
CREATE INDEX idx_tom_type            ON test_object_model (model_type);
CREATE INDEX idx_tom_status          ON test_object_model (status);
CREATE INDEX idx_tom_source          ON test_object_model (source_type, source_ref_id);
CREATE INDEX idx_tom_project_type    ON test_object_model (project_id, model_type, status);

CREATE INDEX idx_tomr_project        ON test_object_model_relation (project_id);
CREATE INDEX idx_tomr_from           ON test_object_model_relation (from_model_id);
CREATE INDEX idx_tomr_to             ON test_object_model_relation (to_model_id);
CREATE INDEX idx_tomr_type           ON test_object_model_relation (relation_type);
CREATE INDEX idx_tomr_status         ON test_object_model_relation (status);
CREATE INDEX idx_tomr_project_type   ON test_object_model_relation (project_id, relation_type, status);
