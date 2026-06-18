-- V13: generation_task 新增 Mini-TOM 集成字段
-- 支持 DIRECT / MINI_TOM / COMPARE 三种生成模式

ALTER TABLE generation_task
    ADD COLUMN generation_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT'
        COMMENT 'DIRECT | MINI_TOM | COMPARE' AFTER status,
    ADD COLUMN use_mini_tom TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否启用 Mini-TOM 辅助生成' AFTER generation_mode,
    ADD COLUMN mini_tom_context_snapshot LONGTEXT NULL
        COMMENT '匹配到的 ACTIVE TOM JSON 快照' AFTER use_mini_tom,
    ADD COLUMN test_scope_snapshot LONGTEXT NULL
        COMMENT 'TestScopeResult JSON 快照' AFTER mini_tom_context_snapshot,
    ADD COLUMN tom_hit_count INT NOT NULL DEFAULT 0
        COMMENT '匹配到的 TOM 数量' AFTER test_scope_snapshot,
    ADD INDEX idx_gen_task_mode (generation_mode);
