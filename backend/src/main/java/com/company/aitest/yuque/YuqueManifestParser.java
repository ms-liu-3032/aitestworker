package com.company.aitest.yuque;

import com.company.aitest.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class YuqueManifestParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public YuqueExportManifest parse(String json) {
        YuqueExportManifest manifest;
        try {
            manifest = MAPPER.readValue(json, YuqueExportManifest.class);
        } catch (Exception e) {
            throw new BusinessException("manifest JSON 解析失败: " + e.getMessage());
        }
        validate(manifest);
        return manifest;
    }

    private void validate(YuqueExportManifest manifest) {
        if (manifest.exporterType() == null || manifest.exporterType().isBlank()) {
            throw new BusinessException("exporterType 为必填字段");
        }
        if (manifest.repoKey() == null || manifest.repoKey().isBlank()) {
            throw new BusinessException("repoKey 为必填字段");
        }
        if (manifest.docs() == null || manifest.docs().isEmpty()) {
            throw new BusinessException("docs 不能为空");
        }
        for (int i = 0; i < manifest.docs().size(); i++) {
            YuqueExportDoc doc = manifest.docs().get(i);
            String prefix = "docs[" + i + "].";
            if (doc.sourceDocId() == null || doc.sourceDocId().isBlank()) {
                throw new BusinessException(prefix + "sourceDocId 为必填字段");
            }
            if (doc.title() == null || doc.title().isBlank()) {
                throw new BusinessException(prefix + "title 为必填字段");
            }
            if (doc.localPath() == null || doc.localPath().isBlank()) {
                throw new BusinessException(prefix + "localPath 为必填字段");
            }
        }
    }
}
