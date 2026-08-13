ALTER TABLE model_config
    ADD COLUMN vision_enabled TINYINT NULL
        COMMENT 'NULL=自动识别，1=支持 OpenAI 兼容图片理解，0=不支持图片理解'
        AFTER endpoint;
