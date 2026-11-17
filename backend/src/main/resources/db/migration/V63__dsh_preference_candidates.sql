-- DSH 可提出个人用例写作偏好候选；只有本人确认才写入生效偏好。
CREATE TABLE dsh_preference_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    proposed_style_text TEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    source_draft_id BIGINT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL,
    decided_at DATETIME NULL,
    INDEX idx_dsh_preference_candidate_owner (user_id, project_id, status, id)
);
