CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64),
    role_code VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_user_username (username)
);

CREATE TABLE project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_name VARCHAR(128) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE project_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    project_role VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_project_member (project_id, user_id)
);

CREATE TABLE model_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_name VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    endpoint VARCHAR(512),
    api_key_encrypted TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE prompt_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prompt_name VARCHAR(128) NOT NULL,
    prompt_type VARCHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    contributor_user_id BIGINT,
    contributor_username VARCHAR(64),
    created_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_prompt_hash (content_hash)
);

CREATE TABLE generation_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    task_name VARCHAR(255),
    requirement_text LONGTEXT NOT NULL,
    requirement_type VARCHAR(64),
    requirement_type_confidence DECIMAL(5,2),
    current_stage VARCHAR(64) NOT NULL DEFAULT 'CREATED',
    status VARCHAR(64) NOT NULL DEFAULT 'CREATED',
    model_config_id BIGINT,
    prompt_snapshot LONGTEXT,
    context_snapshot LONGTEXT,
    quality_score DECIMAL(5,2),
    export_file_id BIGINT,
    confirmed_at DATETIME,
    exported_at DATETIME,
    created_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_generation_project (project_id)
);

CREATE TABLE generation_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    stage VARCHAR(64) NOT NULL,
    question_text TEXT NOT NULL,
    question_reason TEXT,
    question_source VARCHAR(64),
    question_level VARCHAR(32),
    options_json TEXT,
    answer_text TEXT,
    answer_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    answered_by BIGINT,
    answered_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_question_task_stage (task_id, stage)
);

CREATE TABLE skill_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    skill_name VARCHAR(128) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    input_snapshot LONGTEXT,
    output_snapshot LONGTEXT,
    model_config_id BIGINT,
    prompt_snapshot LONGTEXT,
    allowed_tools_json TEXT,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    error_message TEXT,
    token_input INT DEFAULT 0,
    token_output INT DEFAULT 0,
    cost_amount DECIMAL(12,6),
    started_at DATETIME,
    finished_at DATETIME,
    duration_ms BIGINT,
    created_at DATETIME NOT NULL,
    INDEX idx_skill_task (task_id),
    INDEX idx_skill_name (skill_name)
);

CREATE TABLE test_point_draft (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    module_name VARCHAR(255),
    point_content TEXT NOT NULL,
    test_type VARCHAR(64),
    design_method VARCHAR(64),
    suggested_priority VARCHAR(16),
    source_summary TEXT,
    source_refs_json TEXT,
    is_assumption TINYINT NOT NULL DEFAULT 0,
    assumption_note TEXT,
    assumption_confirm_status VARCHAR(32) DEFAULT 'PENDING',
    confidence DECIMAL(5,2),
    compliance_mark VARCHAR(32) NOT NULL DEFAULT 'UNMARKED',
    user_feedback TEXT,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_test_point_task (task_id)
);

CREATE TABLE test_case_draft (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    test_point_id BIGINT,
    case_no VARCHAR(64),
    case_title VARCHAR(255),
    project_name VARCHAR(128),
    module_name VARCHAR(255),
    precondition TEXT,
    steps TEXT,
    expected_result TEXT,
    priority VARCHAR(16),
    case_type VARCHAR(64),
    design_method VARCHAR(64),
    source_refs_json TEXT,
    is_assumption TINYINT NOT NULL DEFAULT 0,
    assumption_note TEXT,
    compliance_mark VARCHAR(32) NOT NULL DEFAULT 'UNMARKED',
    user_feedback TEXT,
    quality_status VARCHAR(32),
    version_no INT NOT NULL DEFAULT 1,
    asset_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_test_case_task (task_id)
);

CREATE TABLE test_case_asset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    source_task_id BIGINT NOT NULL,
    source_draft_id BIGINT NOT NULL,
    case_no VARCHAR(64),
    case_title VARCHAR(255),
    project_name VARCHAR(128),
    module_name VARCHAR(255),
    precondition TEXT,
    steps TEXT,
    expected_result TEXT,
    priority VARCHAR(16),
    case_type VARCHAR(64),
    design_method VARCHAR(64),
    source_refs_json TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE generation_source_ref (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(128),
    source_title VARCHAR(255),
    source_url VARCHAR(1024),
    evidence_text TEXT,
    created_at DATETIME NOT NULL
);

CREATE TABLE quality_check_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    report_json LONGTEXT,
    score DECIMAL(5,2),
    created_at DATETIME NOT NULL
);

CREATE TABLE knowledge_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_doc_id VARCHAR(255) NOT NULL,
    repo_key VARCHAR(255),
    title VARCHAR(255) NOT NULL,
    doc_path VARCHAR(512),
    source_url VARCHAR(1024),
    raw_content_ref VARCHAR(512),
    markdown_content LONGTEXT,
    plain_text LONGTEXT,
    content_hash VARCHAR(128) NOT NULL,
    doc_status VARCHAR(64) NOT NULL DEFAULT 'IMPORTED',
    sync_task_id BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_knowledge_source (project_id, source_type, source_doc_id)
);

CREATE TABLE knowledge_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_no INT NOT NULL,
    chunk_title VARCHAR(255),
    heading_path VARCHAR(512),
    chunk_content TEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    vector_id VARCHAR(128),
    vector_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    embedding_model VARCHAR(128),
    embedding_dim INT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE file_resource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(64),
    storage_type VARCHAR(32) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    content_hash VARCHAR(128),
    created_by BIGINT,
    created_at DATETIME NOT NULL
);

CREATE TABLE operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT,
    action VARCHAR(128) NOT NULL,
    target_type VARCHAR(64),
    target_id BIGINT,
    detail TEXT,
    created_at DATETIME NOT NULL
);

CREATE TABLE task_status_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_type VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    from_status VARCHAR(64),
    to_status VARCHAR(64) NOT NULL,
    event_name VARCHAR(64),
    message TEXT,
    created_by BIGINT,
    created_at DATETIME NOT NULL
);
