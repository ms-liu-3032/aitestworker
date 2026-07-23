package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntentRecognizerTest {

    private final IntentRecognizer recognizer = new IntentRecognizer();

    @Test
    void clarificationAnswerWithConfirmWordsIsSupplementInWaitingStage() {
        String answer = """
                规则配置的具体入口需要确认：在申请人管理中新增管理页面。
                适用对象字段为单选。
                批量配置不支持。
                """;

        assertEquals(
                UserIntent.SUPPLEMENT_REQUIREMENT,
                recognizer.recognize(answer, "WAITING_USER_CONFIRMATION")
        );
    }

    @Test
    void shortClarificationAnswerIsSupplementInWaitingStage() {
        assertEquals(
                UserIntent.SUPPLEMENT_REQUIREMENT,
                recognizer.recognize("单选", "WAITING_USER_CONFIRMATION")
        );
        assertEquals(
                UserIntent.SUPPLEMENT_REQUIREMENT,
                recognizer.recognize("不支持", "WAITING_USER_CONFIRMATION")
        );
    }

    @Test
    void explicitReanalysisCommandTriggersReanalysis() {
        assertEquals(
                UserIntent.REANALYZE_REQUIREMENT,
                recognizer.recognize("需求分析", "WAITING_USER_CONFIRMATION")
        );
        assertEquals(
                UserIntent.REANALYZE_REQUIREMENT,
                recognizer.recognize("重新分析", "ANALYSIS_READY")
        );
    }

    @Test
    void generationCommandStillGeneratesCases() {
        assertEquals(
                UserIntent.GENERATE_CASES,
                recognizer.recognize("生成用例", "WAITING_USER_CONFIRMATION")
        );
        assertEquals(
                UserIntent.GENERATE_CASES,
                recognizer.recognize("生成用例", "ASK_TOM_MODE")
        );
    }

    @Test
    void standaloneConfirmStillConfirmsWhenNoSupplementText() {
        assertEquals(
                UserIntent.CONFIRM_ANALYSIS,
                recognizer.recognize("可以", "WAITING_USER_CONFIRMATION")
        );
        assertEquals(
                UserIntent.CONFIRM_ANALYSIS,
                recognizer.recognize("确认分析", "WAITING_USER_CONFIRMATION")
        );
    }

    @Test
    void explicitTomModeChoiceDoesNotMistakeRequirementTextForModeSelection() {
        assertTrue(recognizer.isExplicitTomModeChoice("使用 TOM"));
        assertTrue(recognizer.isExplicitTomModeChoice("只使用项目 TOM"));
        assertTrue(recognizer.isExplicitTomModeChoice("不使用 TOM，直接分析"));
        assertFalse(recognizer.isExplicitTomModeChoice(
                "补充说明：项目 TOM 里的预约页面已废弃，本需求改为新的审批入口"));
        assertFalse(recognizer.isExplicitTomModeChoice("重新分析"));
    }
}
