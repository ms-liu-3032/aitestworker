package com.company.aitest.yuque;

import java.util.List;

public record YuqueExportDoc(String sourceDocId, String title, String path, String sourceUrl, String localPath,
                             List<String> assetPaths, String updatedAt) {
}
