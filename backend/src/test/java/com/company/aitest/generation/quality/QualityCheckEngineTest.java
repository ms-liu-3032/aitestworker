package com.company.aitest.generation.quality;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QualityCheckEngineTest {

    private final QualityCheckEngine engine = new QualityCheckEngine();

    private static QualityCheckCaseInput validCase(int caseNo) {
        return new QualityCheckCaseInput(caseNo, "登录模块", "用户登录" + caseNo,
                "1. 打开页面\n2. 输入账号" + caseNo + "\n3. 点击登录",
                "页面跳转到首页，显示用户名" + caseNo, "P0", "需求文档 v2.1", true);
    }

    @Test
    void allValidCasesProduceNoIssues() {
        List<QualityCheckCaseInput> inputs = List.of(validCase(1), validCase(2));
        QualityCheckResult result = engine.evaluate(inputs);
        assertEquals(0, result.issues().size());
    }

    @Test
    void moduleEmptyDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, null, "title", "steps", "expected", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "MODULE_EMPTY".equals(i.issueCode())));
    }

    @Test
    void moduleBlankDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "   ", "title", "steps", "expected", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "MODULE_EMPTY".equals(i.issueCode())));
    }

    @Test
    void stepsEmptyDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", null, "expected", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "STEPS_EMPTY".equals(i.issueCode())));
    }

    @Test
    void expectedEmptyDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "EXPECTED_EMPTY".equals(i.issueCode())));
    }

    @Test
    void levelInvalidDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "expected", "P5", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "LEVEL_INVALID".equals(i.issueCode())));
    }

    @Test
    void levelNullDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "expected", null, "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "LEVEL_INVALID".equals(i.issueCode())));
    }

    @Test
    void levelP0ToP4Valid() {
        for (String level : List.of("P0", "P1", "P2", "P3", "P4")) {
            QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "expected", level, "src", true);
            QualityCheckResult result = engine.evaluate(List.of(input));
            assertFalse(result.issues().stream().anyMatch(i -> "LEVEL_INVALID".equals(i.issueCode())),
                    "Level " + level + " should be valid");
        }
    }

    @Test
    void duplicateCaseNoDetected() {
        QualityCheckCaseInput a = new QualityCheckCaseInput(1, "modA", "titleA", "stepsA", "expected", "P0", "src", true);
        QualityCheckCaseInput b = new QualityCheckCaseInput(1, "modB", "titleB", "stepsB", "expected", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(a, b));
        List<QualityCheckIssue> dupes = result.issues().stream()
                .filter(i -> "DUPLICATE_CASE_NO".equals(i.issueCode())).toList();
        assertEquals(2, dupes.size());
        assertTrue(dupes.stream().allMatch(i -> i.caseNo() == 1));
    }

    @Test
    void uniqueCaseNosOk() {
        QualityCheckResult result = engine.evaluate(List.of(validCase(1), validCase(2), validCase(3)));
        assertFalse(result.issues().stream().anyMatch(i -> "DUPLICATE_CASE_NO".equals(i.issueCode())));
    }

    @Test
    void suspectedDuplicateBySameTitle() {
        QualityCheckCaseInput a = new QualityCheckCaseInput(1, "mod", "用户登录功能", "stepsA", "expected", "P0", "src", true);
        QualityCheckCaseInput b = new QualityCheckCaseInput(2, "mod", "用户登录功能", "stepsB", "expected", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(a, b));
        assertTrue(result.issues().stream().anyMatch(i -> "SUSPECTED_DUPLICATE".equals(i.issueCode())));
    }

    @Test
    void suspectedDuplicateBySimilarSteps() {
        String sameSteps = "1. 打开登录页面\n2. 输入用户名和密码\n3. 点击登录按钮";
        QualityCheckCaseInput a = new QualityCheckCaseInput(1, "mod", "titleA", sameSteps, "expected", "P0", "src", true);
        QualityCheckCaseInput b = new QualityCheckCaseInput(2, "mod", "titleB", sameSteps, "expected", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(a, b));
        assertTrue(result.issues().stream().anyMatch(i -> "SUSPECTED_DUPLICATE".equals(i.issueCode())));
    }

    @Test
    void fieldValidationOverSplitDetected() {
        QualityCheckCaseInput a = new QualityCheckCaseInput(1, "用户表单", "姓名必填校验", "1. 清空姓名", "提示必填", "P1", "src", true);
        QualityCheckCaseInput b = new QualityCheckCaseInput(2, "用户表单", "手机号格式校验", "1. 输入错误手机号", "提示格式错误", "P1", "src", true);
        QualityCheckCaseInput c = new QualityCheckCaseInput(3, "用户表单", "地址长度校验", "1. 输入超长地址", "提示超长", "P1", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(a, b, c));
        assertTrue(result.issues().stream().anyMatch(i -> "FIELD_VALIDATION_OVER_SPLIT".equals(i.issueCode())));
    }

    @Test
    void p0ScopeReviewDetectedForNonHappyPath() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "权限模块", "无权限用户不能删除记录",
                "1. 使用无权限账号登录\n2. 点击删除", "提示无权限", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "P0_SCOPE_REVIEW".equals(i.issueCode())));
    }

    @Test
    void vagueExpressionDetected() {
        for (String vague : List.of("功能正常", "显示正常", "符合预期", "正常展示")) {
            QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "验证" + vague, "P0", "src", true);
            QualityCheckResult result = engine.evaluate(List.of(input));
            assertTrue(result.issues().stream().anyMatch(i -> "VAGUE_EXPRESSION".equals(i.issueCode())),
                    "Should detect vague: " + vague);
        }
    }

    @Test
    void vagueExpressionNotTriggeredForNormalText() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "显示登录成功页面", "P0", "src", true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertFalse(result.issues().stream().anyMatch(i -> "VAGUE_EXPRESSION".equals(i.issueCode())));
    }

    @Test
    void sourceEmptyDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "expected", "P0", null, true);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "SOURCE_EMPTY".equals(i.issueCode())));
    }

    @Test
    void aiAssumptionNotMarkedDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "expected", "P0", "src", false);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "AI_ASSUMPTION_UNMARKED".equals(i.issueCode())));
    }

    @Test
    void aiAssumptionNullDetected() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(1, "mod", "title", "steps", "expected", "P0", "src", null);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.issues().stream().anyMatch(i -> "AI_ASSUMPTION_UNMARKED".equals(i.issueCode())));
    }

    @Test
    void issueContainsAllRequiredFields() {
        QualityCheckCaseInput input = new QualityCheckCaseInput(7, null, "title", null, "", "P9", "", false);
        QualityCheckResult result = engine.evaluate(List.of(input));
        assertTrue(result.hasIssues());
        for (QualityCheckIssue issue : result.issues()) {
            assertNotNull(issue.issueCode());
            assertNotNull(issue.severity());
            assertEquals(7, issue.caseNo());
            assertNotNull(issue.message());
            assertNotNull(issue.suggestion());
        }
    }

    @Test
    void issuesSortedByCaseNo() {
        QualityCheckCaseInput a = new QualityCheckCaseInput(5, null, "t", null, null, null, null, null);
        QualityCheckCaseInput b = new QualityCheckCaseInput(1, null, "t", null, null, null, null, null);
        QualityCheckResult result = engine.evaluate(List.of(a, b));
        assertTrue(result.issues().size() > 1);
        int prev = -1;
        for (QualityCheckIssue i : result.issues()) {
            assertTrue(i.caseNo() >= prev);
            prev = i.caseNo();
        }
    }

    @Test
    void hasIssuesReturnsFalseWhenEmpty() {
        QualityCheckResult empty = new QualityCheckResult(List.of());
        assertFalse(empty.hasIssues());
    }

    @Test
    void hasIssuesReturnsTrueWhenNotEmpty() {
        QualityCheckIssue issue = new QualityCheckIssue("C", "WARN", 1, "msg", "sug");
        QualityCheckResult nonEmpty = new QualityCheckResult(List.of(issue));
        assertTrue(nonEmpty.hasIssues());
    }

    @Test
    void multipleIssuesForSingleCase() {
        QualityCheckCaseInput bad = new QualityCheckCaseInput(1, null, "title", null, null, null, null, null);
        QualityCheckResult result = engine.evaluate(List.of(bad));
        assertTrue(result.issues().size() > 1, "Single bad case should trigger multiple issues");
    }
}
