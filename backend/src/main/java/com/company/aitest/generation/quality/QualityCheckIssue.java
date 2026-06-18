package com.company.aitest.generation.quality;

public record QualityCheckIssue(String issueCode, String severity, int caseNo, String message, String suggestion) {
}
