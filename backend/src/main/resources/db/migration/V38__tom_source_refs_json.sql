-- V38: TOM 回灌结构化来源追溯
ALTER TABLE test_object_model
    ADD COLUMN source_refs_json JSON NULL
        COMMENT '结构化来源追溯（cluster/event/stage/type/issue），通用字段不限于 Loop'
        AFTER source_context;
