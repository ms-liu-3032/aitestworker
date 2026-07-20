-- V48: 将历史知识文档默认来源收口为通用平台语义。

UPDATE knowledge_document
SET source_type = 'DOCUMENT'
WHERE source_type = CONCAT('YU', 'QUE');

UPDATE knowledge_document
SET trust_level = 'KNOWLEDGE_DOC'
WHERE trust_level = CONCAT('YU', 'QUE_DOC');

ALTER TABLE knowledge_document
    MODIFY COLUMN trust_level VARCHAR(32) NOT NULL DEFAULT 'KNOWLEDGE_DOC';
