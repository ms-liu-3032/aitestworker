-- V30: business_pack 自动刷新诊断
-- 让自动沉淀链路的成功、失败和输入资产规模可查询、可展示。

CREATE TABLE business_pack_refresh_diagnostic (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    status                  VARCHAR(32) NOT NULL COMMENT 'SUCCESS | FAILED',
    error_message           TEXT NULL,
    tom_count               INT NOT NULL DEFAULT 0,
    page_profile_count      INT NOT NULL DEFAULT 0,
    pattern_count           INT NOT NULL DEFAULT 0,
    summary_count           INT NOT NULL DEFAULT 0,
    generated_pack_count    INT NOT NULL DEFAULT 0,
    inferred_relation_count INT NOT NULL DEFAULT 0,
    started_at              DATETIME NOT NULL,
    finished_at             DATETIME NOT NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bp_refresh_project (project_id, created_at),
    INDEX idx_bp_refresh_status (project_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='业务包自动刷新诊断：记录自动沉淀链路的执行结果和输入规模';
