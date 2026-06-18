package com.company.aitest.generation.session;

import java.time.LocalDateTime;

public record RequirementAnalysisRecord(
        Long id,
        Long sessionId,
        int version,
        int subVersion,
        String requirementText,
        String analysisResult,
        String tomScopeSnapshot,
        String clarificationQuestions,
        String clarificationAnswers,
        String assumptions,
        String testPoints,
        String affectedCases,
        String changeScope,
        String newCasesNeeded,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
