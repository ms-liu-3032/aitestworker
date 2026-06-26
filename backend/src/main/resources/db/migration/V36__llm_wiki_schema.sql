-- V36: LLM Wiki 三层知识包数据模型

-- 知识包：三层 scope（PROJECT / REUSABLE / SYSTEM）
CREATE TABLE wiki_pack (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id              BIGINT NOT NULL,
    scope                   VARCHAR(16) NOT NULL DEFAULT 'PROJECT',
    name                    VARCHAR(255) NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    review_status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    trust_level             VARCHAR(32) NULL,
    source_type             VARCHAR(32) NULL,
    description             TEXT NULL,
    created_by              BIGINT NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wiki_pack_project_name (project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 知识条目
CREATE TABLE wiki_entry (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id                 BIGINT NOT NULL,
    entry_type              VARCHAR(32) NOT NULL,
    title                   VARCHAR(512) NOT NULL,
    content                 TEXT NOT NULL,
    keywords_json           TEXT NULL,
    source_refs_json        TEXT NULL,
    review_status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    confidence              DECIMAL(3,2) NULL,
    effective_status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by              BIGINT NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_wiki_entry_pack (pack_id),
    INDEX idx_wiki_entry_type (entry_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 知识条目关联（与 TOM / Business Pack 关联）
CREATE TABLE wiki_entry_relation (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id                BIGINT NOT NULL,
    related_tom_id          BIGINT NULL,
    related_business_pack_id BIGINT NULL,
    relation_type           VARCHAR(32) NOT NULL,
    INDEX idx_wiki_rel_entry (entry_id),
    INDEX idx_wiki_rel_tom (related_tom_id),
    INDEX idx_wiki_rel_bp (related_business_pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
