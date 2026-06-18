package com.company.aitest.generation.quality;

public record QualityCheckCaseInput(int caseNo, String module, String title, String steps, String expected, String level,
                                    String source, Boolean aiAssumptionMarked) {
}
