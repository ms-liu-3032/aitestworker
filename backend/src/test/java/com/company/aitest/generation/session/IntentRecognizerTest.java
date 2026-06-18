package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IntentRecognizerTest {

    private final IntentRecognizer recognizer = new IntentRecognizer();

    @Test
    void clarificationAnswerWithConfirmWordsIsSupplementInWaitingStage() {
        String answer = """
                规则配置的具体入口需要确认：在系统管理中新增配置页面。
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
}
