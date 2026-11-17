-- Context Contract, Reasoning Skill plan and historical formal-case TOM evidence.
-- All generated knowledge remains a reviewable candidate until an operator confirms it.

CREATE TABLE context_contract (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    analysis_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'FROZEN',
    requirement_snapshot MEDIUMTEXT NOT NULL,
    facts_json MEDIUMTEXT NOT NULL,
    background_json MEDIUMTEXT NOT NULL,
    evidence_json MEDIUMTEXT NOT NULL,
    conflict_json MEDIUMTEXT NULL,
    prompt_section MEDIUMTEXT NULL,
    model_config_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    frozen_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_context_contract_analysis_version (analysis_id, version),
    INDEX idx_context_contract_session (session_id, status),
    INDEX idx_context_contract_project (project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE context_contract_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    layer_name VARCHAR(32) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_ref_id VARCHAR(128) NULL,
    source_version VARCHAR(64) NULL,
    asset_key VARCHAR(191) NOT NULL,
    title VARCHAR(512) NOT NULL,
    content_excerpt TEXT NULL,
    evidence_level VARCHAR(32) NOT NULL DEFAULT 'BACKGROUND',
    priority_rank INT NOT NULL DEFAULT 0,
    included TINYINT(1) NOT NULL DEFAULT 1,
    conflict_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_context_contract_item (contract_id, asset_key),
    INDEX idx_context_contract_item_contract (contract_id, layer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reasoning_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    trigger_types_json TEXT NOT NULL,
    method_rules_json MEDIUMTEXT NOT NULL,
    quality_rules_json MEDIUMTEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_reasoning_skill_key_version (skill_key, version),
    INDEX idx_reasoning_skill_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reasoning_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    analysis_id BIGINT NOT NULL,
    context_contract_id BIGINT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    plan_json MEDIUMTEXT NOT NULL,
    summary_json MEDIUMTEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_reasoning_plan_analysis_version (analysis_id, version),
    INDEX idx_reasoning_plan_session (session_id, status),
    INDEX idx_reasoning_plan_project (project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tom_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    tom_id BIGINT NULL,
    relation_id BIGINT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_ref_type VARCHAR(64) NOT NULL,
    source_ref_id BIGINT NOT NULL,
    source_version INT NOT NULL DEFAULT 1,
    source_hash CHAR(64) NOT NULL,
    source_excerpt TEXT NULL,
    evidence_level VARCHAR(32) NOT NULL DEFAULT 'HISTORICAL',
    conflict_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    candidate_key VARCHAR(191) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_tom_evidence_source (project_id, source_type, source_ref_id),
    INDEX idx_tom_evidence_tom (tom_id, relation_id),
    INDEX idx_tom_evidence_review (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE test_object_model_relation
    ADD COLUMN source_refs_json MEDIUMTEXT NULL AFTER source_ref_id,
    ADD COLUMN candidate_key VARCHAR(191) NULL AFTER source_refs_json,
    ADD COLUMN source_hash CHAR(64) NULL AFTER candidate_key,
    ADD COLUMN source_version INT NOT NULL DEFAULT 1 AFTER source_hash,
    ADD COLUMN requires_human_confirm TINYINT(1) NOT NULL DEFAULT 1 AFTER source_version,
    ADD COLUMN confirmed_by BIGINT NULL AFTER requires_human_confirm,
    ADD COLUMN confirmed_at DATETIME NULL AFTER confirmed_by,
    ADD COLUMN rejected_by BIGINT NULL AFTER confirmed_at,
    ADD COLUMN rejected_at DATETIME NULL AFTER rejected_by,
    ADD COLUMN rejected_reason VARCHAR(512) NULL AFTER rejected_at,
    ADD INDEX idx_tom_relation_candidate (project_id, candidate_key, source_version),
    ADD INDEX idx_tom_relation_source_hash (project_id, source_hash);

INSERT INTO reasoning_skill(skill_key, display_name, description, trigger_types_json, method_rules_json, quality_rules_json, version, status, created_at, updated_at)
SELECT 'FIELD_BOUNDARY', '字段与边界推理', '字段输入、格式、范围与必填规则的测试推理策略。',
       '["FIELD","FORM","INPUT","BOUNDARY"]',
       '{"methods":["等价类划分法","边界值分析法"],"obligations":["有效等价类","无效等价类","最小/最大/临界值","必填与格式"]}',
       '{"rules":["不得为每个字段机械重复相同步骤","边界值必须可执行且有明确预期"]}', 1, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM reasoning_skill WHERE skill_key = 'FIELD_BOUNDARY' AND version = 1);

INSERT INTO reasoning_skill(skill_key, display_name, description, trigger_types_json, method_rules_json, quality_rules_json, version, status, created_at, updated_at)
SELECT 'STATE_FLOW', '流程与状态推理', '流程、审批、生命周期和回退场景的测试推理策略。',
       '["FLOW","STATE","APPROVAL","PROCESS"]',
       '{"methods":["场景法","状态迁移法"],"obligations":["主流程","合法迁移","非法迁移","回退/重试"]}',
       '{"rules":["完整流程用例保留跨节点链路","单节点用例只验证本节点行为"]}', 1, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM reasoning_skill WHERE skill_key = 'STATE_FLOW' AND version = 1);

INSERT INTO reasoning_skill(skill_key, display_name, description, trigger_types_json, method_rules_json, quality_rules_json, version, status, created_at, updated_at)
SELECT 'RULE_COMBINATION', '规则组合推理', '多条件、资格、优惠、审批和配置规则的推理策略。',
       '["RULE","BRANCH","COMBINATION"]',
       '{"methods":["判定表法","因果图法"],"obligations":["命中规则","不命中规则","关键条件组合","冲突优先级"]}',
       '{"rules":["只枚举影响结果的组合","未知规则必须标记为待确认"]}', 1, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM reasoning_skill WHERE skill_key = 'RULE_COMBINATION' AND version = 1);

INSERT INTO reasoning_skill(skill_key, display_name, description, trigger_types_json, method_rules_json, quality_rules_json, version, status, created_at, updated_at)
SELECT 'ACCESS_INTEGRATION', '权限与数据集成推理', '权限、隔离、同步、幂等和第三方集成的推理策略。',
       '["AUTH","DATA","INTEGRATION","CONCURRENCY","IDEMPOTENT"]',
       '{"methods":["权限矩阵","数据一致性验证","错误推测法"],"obligations":["有权限/无权限","数据隔离","失败重试","重复请求","同步结果"]}',
       '{"rules":["未经证实的外部行为不能写为事实","高风险数据场景必须保留证据来源"]}', 1, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM reasoning_skill WHERE skill_key = 'ACCESS_INTEGRATION' AND version = 1);
