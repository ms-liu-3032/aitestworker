-- External, manually authored historical cases are evidence only.  They never enter the formal case library.
CREATE TABLE historical_case_import (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    batch_name VARCHAR(255) NOT NULL,
    business_domain VARCHAR(128) NULL,
    source_format VARCHAR(32) NOT NULL,
    item_count INT NOT NULL DEFAULT 0,
    eligible_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'IMPORTED',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_historical_case_import_project (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE historical_case_import_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    case_title VARCHAR(512) NOT NULL,
    module_name VARCHAR(255) NULL,
    precondition MEDIUMTEXT NULL,
    steps MEDIUMTEXT NULL,
    expected_result MEDIUMTEXT NULL,
    priority VARCHAR(32) NULL,
    case_type VARCHAR(64) NULL,
    design_method VARCHAR(128) NULL,
    source_hash CHAR(64) NOT NULL,
    eligibility_status VARCHAR(32) NOT NULL DEFAULT 'ELIGIBLE',
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_historical_case_import_item (import_id, source_hash),
    INDEX idx_historical_case_import_item_project (project_id, import_id),
    CONSTRAINT fk_historical_case_import_item_batch FOREIGN KEY (import_id) REFERENCES historical_case_import(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
