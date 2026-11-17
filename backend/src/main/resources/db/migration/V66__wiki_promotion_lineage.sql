-- Preserve the reviewed source when a Wiki pack is submitted to a broader scope.
ALTER TABLE wiki_pack
    ADD COLUMN promotion_source_pack_id BIGINT NULL AFTER source_type,
    ADD INDEX idx_wiki_pack_promotion_source (promotion_source_pack_id, scope, review_status);

ALTER TABLE wiki_entry
    ADD COLUMN promotion_source_entry_id BIGINT NULL AFTER source_refs_json,
    ADD INDEX idx_wiki_entry_promotion_source (promotion_source_entry_id);
