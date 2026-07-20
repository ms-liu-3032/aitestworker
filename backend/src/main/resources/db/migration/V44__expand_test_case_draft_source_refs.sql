-- Per-draft traceability is structured JSON.  It may legitimately grow beyond TEXT for
-- historical drafts or provider metadata, so it must not block a completed generation task.
ALTER TABLE test_case_draft
    MODIFY COLUMN source_refs_json LONGTEXT NULL;
