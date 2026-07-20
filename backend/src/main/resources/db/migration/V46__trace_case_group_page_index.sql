CREATE INDEX idx_trace_case_project_group_user_updated
    ON trace_generated_case(project_id, trace_group_id, user_id, updated_at, id);
