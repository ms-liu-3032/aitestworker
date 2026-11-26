CREATE TABLE ui_coverage_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    task_key VARCHAR(128) NOT NULL,
    target_origin VARCHAR(512) NOT NULL,
    inventory_status VARCHAR(16) NOT NULL DEFAULT 'UNCONFIRMED',
    created_by BIGINT NOT NULL,
    confirmed_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    confirmed_at DATETIME NULL,
    UNIQUE KEY uk_ui_coverage_task (project_id, created_by, task_key),
    INDEX idx_ui_coverage_project (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ui_execution_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL DEFAULT 0,
    rule_code VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    check_kind VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT NULL,
    reviewed_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    reviewed_at DATETIME NULL,
    UNIQUE KEY uk_ui_execution_rule (project_id, rule_code, version_no),
    INDEX idx_ui_execution_rule_active (project_id, status, rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ui_coverage_task_rule (
    task_id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    rule_version INT NOT NULL,
    check_kind VARCHAR(32) NOT NULL,
    applicability VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    reason VARCHAR(1000) NULL,
    PRIMARY KEY (task_id, rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ui_execution_rule(project_id, rule_code, version_no, title, check_kind, status, created_at) VALUES
 (0, 'UI_REQUIRED_FIELD', 1, '业务必填条件逐项正反验证', 'FIELD_PAIR', 'ACTIVE', NOW()),
 (0, 'UI_EXPECTED_BEHAVIOR', 1, '业务主流程和可观察预期', 'RUN_ASSERTION', 'ACTIVE', NOW()),
 (0, 'UI_ERROR_HANDLING', 1, '异常路径与错误反馈', 'RUN_ASSERTION', 'ACTIVE', NOW()),
 (0, 'UI_STATE_TRANSITION', 1, '状态迁移与结果可见性', 'RUN_ASSERTION', 'ACTIVE', NOW()),
 (0, 'UI_PERMISSION_BOUNDARY', 1, '角色与权限边界', 'RUN_ASSERTION', 'ACTIVE', NOW());

CREATE TABLE ui_coverage_obligation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    field_key VARCHAR(128) NOT NULL,
    page_path VARCHAR(512) NOT NULL,
    field_label VARCHAR(255) NOT NULL,
    source_basis VARCHAR(1000) NOT NULL,
    applicability VARCHAR(16) NOT NULL DEFAULT 'REQUIRED',
    positive_run_id BIGINT NULL,
    negative_run_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_ui_coverage_field (task_id, rule_code, field_key),
    INDEX idx_ui_coverage_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
