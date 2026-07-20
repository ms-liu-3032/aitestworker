-- Conversation messages are an audit trail and may contain analysis summaries.
-- Older installations can still have a short content column, which must not block
-- a completed analysis from persisting its structured result.
ALTER TABLE generation_message
    MODIFY COLUMN content LONGTEXT NULL;
