package com.company.aitest.generation.quality;

import java.util.List;

@FunctionalInterface
public interface QualityCheckRule {
    List<QualityCheckIssue> check(List<QualityCheckCaseInput> inputs);
}
