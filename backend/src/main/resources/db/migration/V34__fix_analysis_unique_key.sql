-- V34: 修正 requirement_analysis 唯一键，支持增量分析的 sub_version
-- 原 uk_ra_session_version(session_id, version) 不允许同 version 多条记录
-- 改为 (session_id, version, sub_version) 支持增量分析

ALTER TABLE requirement_analysis DROP INDEX uk_ra_session_version;
ALTER TABLE requirement_analysis ADD UNIQUE KEY uk_ra_session_version_sub (session_id, version, sub_version);
