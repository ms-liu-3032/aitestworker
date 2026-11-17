package com.company.aitest.dsh;

import java.nio.file.Files;
import java.nio.file.Path;

import com.company.aitest.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DshEvidenceStorageServiceTest {
    @TempDir Path temp;

    @Test
    void storesEvidenceInsideProjectSessionAndRejectsUnsafeFrames() throws Exception {
        var storage = new DshEvidenceStorageService(temp.toString());
        var file = new MockMultipartFile("file", "000001.jpg", "image/jpeg", new byte[]{1, 2, 3});
        var saved = storage.store(7L, 11L, "FRAME", file);
        Path resolved = storage.resolveStored(saved.storagePath());
        assertTrue(resolved.startsWith(temp.resolve("dsh-evidence/7/11")));
        assertEquals(3, Files.size(resolved));
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", saved.sha256());
        assertEquals(saved.sha256(), storage.store(7L, 11L, "FRAME", file).sha256());
        assertThrows(BusinessException.class, () -> storage.store(7L, 11L, "FRAME",
                new MockMultipartFile("file", "000001.jpg", "image/jpeg", new byte[]{4, 5, 6})));
        assertEquals(3, Files.size(resolved));
        assertThrows(BusinessException.class, () -> storage.store(7L, 11L, "FRAME",
                new MockMultipartFile("file", "../../bad.jpg", "image/jpeg", new byte[]{1})));
        assertThrows(BusinessException.class, () -> storage.resolveStored(temp.resolve("outside.jpg").toString()));
    }
}
