package com.company.aitest.generation.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class QualityCheckEngine {

    private static final Set<String> VALID_LEVELS = Set.of("P0", "P1", "P2", "P3", "P4");
    private static final Set<String> VAGUE_PATTERNS = Set.of("功能正常", "显示正常", "符合预期", "正常展示");

    private final List<QualityCheckRule> rules;

    public QualityCheckEngine() {
        this.rules = List.of(
                this::checkModuleEmpty,
                this::checkStepsEmpty,
                this::checkExpectedEmpty,
                this::checkLevelInvalid,
                this::checkDuplicateCaseNo,
                this::checkSuspectedDuplicate,
                this::checkVagueExpression,
                this::checkSourceEmpty,
                this::checkAiAssumptionNotMarked);
    }

    public QualityCheckResult evaluate(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> allIssues = new ArrayList<>();
        for (QualityCheckRule rule : rules) {
            allIssues.addAll(rule.check(inputs));
        }
        allIssues.sort(Comparator.comparingInt(QualityCheckIssue::caseNo));
        return new QualityCheckResult(allIssues);
    }

    private List<QualityCheckIssue> checkModuleEmpty(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (QualityCheckCaseInput input : inputs) {
            if (input.module() == null || input.module().isBlank()) {
                issues.add(new QualityCheckIssue("MODULE_EMPTY", "ERROR", input.caseNo(),
                        "所属模块为空", "请填写用例所属模块"));
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkStepsEmpty(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (QualityCheckCaseInput input : inputs) {
            if (input.steps() == null || input.steps().isBlank()) {
                issues.add(new QualityCheckIssue("STEPS_EMPTY", "ERROR", input.caseNo(),
                        "操作步骤为空", "请填写测试操作步骤"));
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkExpectedEmpty(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (QualityCheckCaseInput input : inputs) {
            if (input.expected() == null || input.expected().isBlank()) {
                issues.add(new QualityCheckIssue("EXPECTED_EMPTY", "ERROR", input.caseNo(),
                        "预期结果为空", "请填写预期结果"));
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkLevelInvalid(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (QualityCheckCaseInput input : inputs) {
            String level = input.level();
            if (level == null || level.isBlank() || !VALID_LEVELS.contains(level.strip().toUpperCase())) {
                issues.add(new QualityCheckIssue("LEVEL_INVALID", "WARN", input.caseNo(),
                        "用例等级不是 P0-P4: " + (level == null ? "null" : level),
                        "请将等级设置为 P0/P1/P2/P3/P4 之一"));
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkDuplicateCaseNo(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        Map<Integer, Long> counts = inputs.stream()
                .collect(Collectors.groupingBy(QualityCheckCaseInput::caseNo, Collectors.counting()));
        for (QualityCheckCaseInput input : inputs) {
            if (counts.get(input.caseNo()) > 1) {
                issues.add(new QualityCheckIssue("DUPLICATE_CASE_NO", "ERROR", input.caseNo(),
                        "编号 " + input.caseNo() + " 重复", "请确保每个用例编号唯一"));
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkSuspectedDuplicate(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            for (int j = i + 1; j < inputs.size(); j++) {
                QualityCheckCaseInput a = inputs.get(i);
                QualityCheckCaseInput b = inputs.get(j);
                boolean sameTitle = a.title() != null && b.title() != null
                        && a.title().strip().equalsIgnoreCase(b.title().strip());
                boolean similarSteps = a.steps() != null && b.steps() != null
                        && a.steps().strip().length() > 10
                        && a.steps().strip().substring(0, Math.min(30, a.steps().strip().length()))
                                .equals(b.steps().strip().substring(0, Math.min(30, b.steps().strip().length())));
                if (sameTitle || similarSteps) {
                    issues.add(new QualityCheckIssue("SUSPECTED_DUPLICATE", "WARN", a.caseNo(),
                            "用例 " + a.caseNo() + " 与 " + b.caseNo() + " 疑似重复",
                            "请检查两条用例是否可合并"));
                    issues.add(new QualityCheckIssue("SUSPECTED_DUPLICATE", "WARN", b.caseNo(),
                            "用例 " + b.caseNo() + " 与 " + a.caseNo() + " 疑似重复",
                            "请检查两条用例是否可合并"));
                }
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkVagueExpression(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (QualityCheckCaseInput input : inputs) {
            String text = (input.expected() != null ? input.expected() : "")
                    + (input.steps() != null ? input.steps() : "");
            for (String vague : VAGUE_PATTERNS) {
                if (text.contains(vague)) {
                    issues.add(new QualityCheckIssue("VAGUE_EXPRESSION", "WARN", input.caseNo(),
                            "存在模糊表达: " + vague, "请用具体可验证的描述替代 \"" + vague + "\""));
                }
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkSourceEmpty(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (QualityCheckCaseInput input : inputs) {
            if (input.source() == null || input.source().isBlank()) {
                issues.add(new QualityCheckIssue("SOURCE_EMPTY", "ERROR", input.caseNo(),
                        "来源为空", "请标记用例来源（需求/缺陷/探索等）"));
            }
        }
        return issues;
    }

    private List<QualityCheckIssue> checkAiAssumptionNotMarked(List<QualityCheckCaseInput> inputs) {
        List<QualityCheckIssue> issues = new ArrayList<>();
        for (QualityCheckCaseInput input : inputs) {
            if (input.aiAssumptionMarked() == null || !input.aiAssumptionMarked()) {
                issues.add(new QualityCheckIssue("AI_ASSUMPTION_UNMARKED", "WARN", input.caseNo(),
                        "AI 假设未标记", "请确认并标记 AI 生成内容中的假设项"));
            }
        }
        return issues;
    }
}
