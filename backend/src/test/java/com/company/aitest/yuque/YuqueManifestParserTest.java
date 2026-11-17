package com.company.aitest.yuque;

import com.company.aitest.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YuqueManifestParserTest {

    private final YuqueManifestParser parser = new YuqueManifestParser();

    @Test
    void parseValidManifest() {
        String json = """
                {
                  "exporterType": "YUQUE_TOOLS",
                  "repoKey": "team/repo",
                  "docs": [
                    {
                      "sourceDocId": "team/repo/doc-slug",
                      "title": "审批规则",
                      "path": "访客系统/预约/审批规则",
                      "sourceUrl": "https://www.yuque.com/team/repo/doc-slug",
                      "localPath": "docs/approval.md",
                      "assetPaths": ["assets/a.png"],
                      "updatedAt": "2026-05-16T10:00:00+08:00"
                    }
                  ]
                }""";
        YuqueExportManifest manifest = parser.parse(json);
        assertEquals("YUQUE_TOOLS", manifest.exporterType());
        assertEquals("team/repo", manifest.repoKey());
        assertEquals(1, manifest.docs().size());
        YuqueExportDoc doc = manifest.docs().get(0);
        assertEquals("team/repo/doc-slug", doc.sourceDocId());
        assertEquals("审批规则", doc.title());
        assertEquals("docs/approval.md", doc.localPath());
    }

    @Test
    void parseMultipleDocs() {
        String json = """
                {
                  "exporterType": "YUQUE_TOOLS",
                  "repoKey": "team/repo",
                  "docs": [
                    {"sourceDocId": "doc-1", "title": "T1", "localPath": "p1.md"},
                    {"sourceDocId": "doc-2", "title": "T2", "localPath": "p2.md"}
                  ]
                }""";
        YuqueExportManifest manifest = parser.parse(json);
        assertEquals(2, manifest.docs().size());
    }

    @Test
    void missingExporterTypeThrowsException() {
        String json = """
                {"repoKey": "team/repo", "docs": [{"sourceDocId": "x", "title": "T", "localPath": "p.md"}]}""";
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("exporterType"));
    }

    @Test
    void blankExporterTypeThrowsException() {
        String json = """
                {"exporterType": "  ", "repoKey": "team/repo", "docs": [{"sourceDocId": "x", "title": "T", "localPath": "p.md"}]}""";
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("exporterType"));
    }

    @Test
    void missingRepoKeyThrowsException() {
        String json = """
                {"exporterType": "YUQUE_TOOLS", "docs": [{"sourceDocId": "x", "title": "T", "localPath": "p.md"}]}""";
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("repoKey"));
    }

    @Test
    void emptyDocsThrowsException() {
        String json = """
                {"exporterType": "YUQUE_TOOLS", "repoKey": "team/repo", "docs": []}""";
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("docs"));
    }

    @Test
    void nullDocsThrowsException() {
        String json = """
                {"exporterType": "YUQUE_TOOLS", "repoKey": "team/repo"}""";
        // null docs triggers JSON parse into null list, which is caught as empty
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("docs"));
    }

    @Test
    void missingSourceDocIdThrowsException() {
        String json = """
                {"exporterType": "YUQUE_TOOLS", "repoKey": "team/repo", "docs": [{"title": "T", "localPath": "p.md"}]}""";
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("sourceDocId"));
    }

    @Test
    void missingTitleThrowsException() {
        String json = """
                {"exporterType": "YUQUE_TOOLS", "repoKey": "team/repo", "docs": [{"sourceDocId": "x", "localPath": "p.md"}]}""";
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("title"));
    }

    @Test
    void missingLocalPathThrowsException() {
        String json = """
                {"exporterType": "YUQUE_TOOLS", "repoKey": "team/repo", "docs": [{"sourceDocId": "x", "title": "T"}]}""";
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("localPath"));
    }

    @Test
    void invalidJsonThrowsException() {
        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parse("not json"));
        assertTrue(ex.getMessage().contains("JSON"));
    }
}
