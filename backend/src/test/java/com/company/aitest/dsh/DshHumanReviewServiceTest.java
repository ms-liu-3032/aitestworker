package com.company.aitest.dsh;

import com.company.aitest.generation.session.GenerationCaseLibraryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DshHumanReviewServiceTest {
    @Test
    void fingerprintChangesWhenReviewedCaseContentChanges() {
        var original = draft("标题", "点击保存", "保存成功");
        var changedStep = draft("标题", "点击删除", "保存成功");
        var changedExpected = draft("标题", "点击保存", "提示失败");

        assertEquals(DshHumanReviewService.fingerprint(original),
                DshHumanReviewService.fingerprint(draft("标题", "点击保存", "保存成功")));
        assertNotEquals(DshHumanReviewService.fingerprint(original), DshHumanReviewService.fingerprint(changedStep));
        assertNotEquals(DshHumanReviewService.fingerprint(original), DshHumanReviewService.fingerprint(changedExpected));
    }

    private static GenerationCaseLibraryService.LocalCaseDraftView draft(String title, String steps, String expected) {
        return new GenerationCaseLibraryService.LocalCaseDraftView(
                1L, 2L, 3L, "DSH-2", title, null, "访客", "已登录", steps, expected,
                "P2", "FUNCTIONAL", "POSITIVE", "等价类", "[]", "PERSONAL", "DRAFT", 1, 4L,
                null, null, null, "GENERATION");
    }
}
