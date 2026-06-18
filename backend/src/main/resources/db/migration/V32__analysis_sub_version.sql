-- V32: requirement_analysis 增加 sub_version 字段
-- sub_version: 0 = 全量分析, 1/2/3 = 增量分析（基于全量版本的第 N 次增量）

ALTER TABLE requirement_analysis
    ADD COLUMN sub_version INT NOT NULL DEFAULT 0
        COMMENT '子版本号：0=全量, 1-3=增量';

CREATE INDEX idx_ra_session_ver_sub ON requirement_analysis(session_id, version, sub_version);
