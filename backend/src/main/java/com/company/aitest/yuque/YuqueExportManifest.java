package com.company.aitest.yuque;

import java.util.List;

public record YuqueExportManifest(String exporterType, String repoKey, List<YuqueExportDoc> docs) {
}
