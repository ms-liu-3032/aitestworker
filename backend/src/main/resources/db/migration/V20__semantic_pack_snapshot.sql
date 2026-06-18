CREATE TABLE semantic_pack (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    pack_key VARCHAR(64) NOT NULL DEFAULT 'PROJECT_AUTO',
    scope VARCHAR(32) NOT NULL DEFAULT 'PROJECT',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    signal_count INT NOT NULL DEFAULT 0,
    term_count INT NOT NULL DEFAULT 0,
    source_hash VARCHAR(64) NULL,
    built_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_semantic_pack_project_key (project_id, pack_key),
    KEY idx_semantic_pack_status (project_id, status),
    KEY idx_semantic_pack_built (project_id, built_at)
);

CREATE TABLE semantic_pack_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pack_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    item_no INT NOT NULL,
    category VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    summary TEXT NULL,
    route_hint VARCHAR(255) NULL,
    score_boost DECIMAL(8,2) NOT NULL DEFAULT 0,
    signal_updated_at DATETIME NULL,
    keywords_json JSON NULL,
    source_ref VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_semantic_pack_item_no (pack_id, item_no),
    KEY idx_semantic_pack_item_project (project_id, category),
    KEY idx_semantic_pack_item_route (project_id, route_hint(191)),
    KEY idx_semantic_pack_item_pack (pack_id, category)
);
