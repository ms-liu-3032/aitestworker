-- Keep the original import item as evidence while making its case available in the formal library.
ALTER TABLE test_case_asset
    ADD COLUMN import_source_type VARCHAR(32) NULL,
    ADD COLUMN import_source_ref_id BIGINT NULL,
    ADD UNIQUE KEY uk_case_asset_import_source (import_source_type, import_source_ref_id),
    MODIFY COLUMN precondition MEDIUMTEXT NULL,
    MODIFY COLUMN steps MEDIUMTEXT NULL,
    MODIFY COLUMN expected_result MEDIUMTEXT NULL;

-- Existing imported batches become searchable formal cases as well. The source link makes
-- the backfill repeat-safe and preserves the original row for TOM/knowledge provenance.
INSERT INTO test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title,
    project_name, module_name, precondition, steps, expected_result, priority, case_type,
    scenario_type, design_method, source_refs_json, created_at, updated_at, case_scope,
    case_status, submitted_by, submitted_at, import_source_type, import_source_ref_id)
SELECT i.project_id, NULL, NULL, CONCAT('IMPORT-', i.id), LEFT(i.case_title, 255),
       p.project_name, i.module_name, i.precondition, i.steps, i.expected_result,
       LEFT(i.priority, 16), i.case_type, 'POSITIVE', LEFT(i.design_method, 64),
       JSON_OBJECT('sourceType', 'MANUAL_IMPORT', 'historicalImportId', i.import_id,
                   'historicalImportItemId', i.id), i.created_at, i.created_at,
       'PROJECT', 'SUBMITTED', b.created_by, i.created_at, 'MANUAL_IMPORT', i.id
FROM historical_case_import_item i
JOIN historical_case_import b ON b.id = i.import_id
JOIN project p ON p.id = i.project_id
LEFT JOIN test_case_asset a ON a.import_source_type = 'MANUAL_IMPORT' AND a.import_source_ref_id = i.id
WHERE a.id IS NULL;
