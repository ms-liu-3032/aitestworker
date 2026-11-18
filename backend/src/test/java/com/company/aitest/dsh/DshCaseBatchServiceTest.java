package com.company.aitest.dsh;

import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DshCaseBatchServiceTest {
    private static final CurrentUser USER = new CurrentUser(7L, "tester", "USER");

    @Test
    void createsIndependentDraftsAndMapsTheirNewIds() {
        DshDraftService drafts = mock(DshDraftService.class);
        AutomationAssetService assets = mock(AutomationAssetService.class);
        var mapping = new ObjectMapper().createObjectNode().put("page", "/list");
        var first = item("draft-1", "asset-1", mapping);
        var second = item("draft-2", null, null);
        when(drafts.save(eq(3L), eq(first.draft()), eq(USER)))
                .thenReturn(new DshDraftService.SavedDraft(41L, "hash"));
        when(drafts.save(eq(3L), eq(second.draft()), eq(USER)))
                .thenReturn(new DshDraftService.SavedDraft(42L, "hash"));
        var asset = mock(AutomationAssetService.Asset.class);
        when(asset.id()).thenReturn(91L);
        when(assets.create(eq(3L), any(), eq(USER))).thenReturn(asset);

        var result = new DshCaseBatchService(drafts, assets).create(3L,
                new DshCaseBatchService.BatchInput(5L, List.of(first, second)), USER);

        assertEquals(2, result.size());
        assertEquals(41L, result.get(0).draftId());
        assertEquals(91L, result.get(0).assetId());
        assertEquals(42L, result.get(1).draftId());
        assertNull(result.get(1).assetId());
        verify(assets).create(eq(3L), argThat(input -> input.caseId() == 41L
                && "DRAFT".equals(input.caseKind()) && "DSH-41".equals(input.mapping().path("caseNo").asText())), eq(USER));
        assertFalse(mapping.has("caseNo"), "batch must not mutate caller mapping");
    }

    @Test
    void rejectsDuplicateKeysBeforeAnyWrite() {
        DshDraftService drafts = mock(DshDraftService.class);
        AutomationAssetService assets = mock(AutomationAssetService.class);
        var item = item("same", null, null);
        assertThrows(BusinessException.class, () -> new DshCaseBatchService(drafts, assets)
                .create(3L, new DshCaseBatchService.BatchInput(5L, List.of(item, item)), USER));
        verifyNoInteractions(drafts, assets);
    }

    private DshCaseBatchService.Item item(String key, String assetKey, com.fasterxml.jackson.databind.JsonNode mapping) {
        var draft = new DshDraftService.DraftInput(5L, key, "需求", "独立用例", "/模块",
                "前置", "1. 执行动作", "1. 显示结果", "P1", "MAIN_FLOW", List.of(), null);
        return new DshCaseBatchService.Item(draft, assetKey, "UI", mapping);
    }
}
