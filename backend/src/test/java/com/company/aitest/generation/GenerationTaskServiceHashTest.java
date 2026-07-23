package com.company.aitest.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenerationTaskServiceHashTest {

    @Test
    void normalizesCurrentAndLegacyTomModesBeforePersistence() {
        assertEquals("PROJECT_AND_SYSTEM_TOM",
                GenerationTaskService.normalizeGenerationMode("PROJECT_AND_SYSTEM_TOM", true));
        assertEquals("PROJECT_TOM",
                GenerationTaskService.normalizeGenerationMode("project_tom", false));
        assertEquals("PROJECT_AND_SYSTEM_TOM",
                GenerationTaskService.normalizeGenerationMode("MINI_TOM", true));
        assertEquals("DIRECT",
                GenerationTaskService.normalizeGenerationMode(null, false));
    }

    @Test
    void requestHashIsStableForSameInputAndChangesForModelOrUser() {
        GenerationTaskService service = new GenerationTaskService(null, null, null, null);

        String a = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A", 1, 10L, 100L);
        String b = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A", 1, 10L, 100L);
        String differentModel = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A", 1, 11L, 100L);
        String differentUser = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A", 1, 10L, 101L);

        assertEquals(a, b);
        assertNotEquals(a, differentModel);
        assertNotEquals(a, differentUser);
    }

    @Test
    void requestHashChangesForPromptContentHashOrModelFingerprint() {
        GenerationTaskService service = new GenerationTaskService(null, null, null, null);

        String base = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A",
                1, 10L, "prompt-hash-a", "OTHER|gpt-a|https://a.example", 100L);
        String differentPromptContent = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A",
                1, 10L, "prompt-hash-b", "OTHER|gpt-a|https://a.example", 100L);
        String differentModelName = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A",
                1, 10L, "prompt-hash-a", "OTHER|gpt-b|https://a.example", 100L);

        assertNotEquals(base, differentPromptContent);
        assertNotEquals(base, differentModelName);
    }

    @Test
    void requestHashChangesForPromptTemplateFingerprint() {
        GenerationTaskService service = new GenerationTaskService(null, null, null, null);

        String base = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A",
                1, 10L, "prompt-hash-a", "promptTemplateId=5|version=1|contentHash=hash-a",
                "OTHER|gpt-a|https://a.example", 100L);
        String differentTemplateVersion = service.buildRequestHash(1L, "TEST_CASE_GENERATION", "需求A",
                1, 10L, "prompt-hash-a", "promptTemplateId=5|version=2|contentHash=hash-b",
                "OTHER|gpt-a|https://a.example", 100L);

        assertNotEquals(base, differentTemplateVersion);
    }
}
