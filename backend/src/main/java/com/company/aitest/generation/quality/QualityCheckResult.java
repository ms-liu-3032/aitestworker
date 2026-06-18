package com.company.aitest.generation.quality;

import java.util.List;

public record QualityCheckResult(List<QualityCheckIssue> issues) {

    public boolean hasIssues() {
        return issues != null && !issues.isEmpty();
    }
}
