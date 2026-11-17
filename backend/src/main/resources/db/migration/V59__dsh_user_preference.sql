CREATE TABLE dsh_user_preference (
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    style_text TEXT NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (user_id, project_id)
);
