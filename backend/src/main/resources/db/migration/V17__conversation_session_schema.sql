-- V17: Conversational generation session tables

CREATE TABLE generation_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    session_title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    model_config_id BIGINT,
    prompt_template_id BIGINT,
    prompt_snapshot LONGTEXT,
    use_mini_tom TINYINT(1) NOT NULL DEFAULT 0,
    latest_analysis_version INT NOT NULL DEFAULT 0,
    execution_task_id BIGINT,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_gs_project (project_id),
    INDEX idx_gs_user_created (created_by, created_at DESC),
    INDEX idx_gs_status (project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE generation_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT,
    structured_payload JSON,
    analysis_version INT NOT NULL DEFAULT 0,
    stage VARCHAR(64),
    created_at DATETIME NOT NULL,
    INDEX idx_gm_session (session_id, id ASC),
    INDEX idx_gm_session_version (session_id, analysis_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE requirement_analysis (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    version INT NOT NULL,
    requirement_text LONGTEXT NOT NULL,
    analysis_result JSON,
    tom_scope_snapshot JSON,
    clarification_questions JSON,
    clarification_answers JSON,
    assumptions JSON,
    test_points JSON,
    status VARCHAR(32) NOT NULL DEFAULT 'ANALYZING',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_ra_session_version (session_id, version),
    INDEX idx_ra_session_status (session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE generation_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    message_id BIGINT,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    content_hash VARCHAR(128),
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    parsed_content LONGTEXT,
    parse_error TEXT,
    vision_result JSON,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_ga_session (session_id),
    INDEX idx_ga_message (message_id),
    INDEX idx_ga_parse_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
