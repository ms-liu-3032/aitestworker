package com.company.aitest.generation;

import java.time.LocalDateTime;

public record GenerationTaskRecord(Long id, Long projectId, String taskName, String requirementText,
                                   String requirementType, String currentStage, String status,
                                   Long modelConfigId, String promptSnapshot,
                                   Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                                   String generationMode, Boolean useMiniTom,
                                   String miniTomContextSnapshot, String testScopeSnapshot,
                                   Integer tomHitCount,
                                   String projectTomSnapshot, String systemTomSnapshot,
                                   String tomWeightConfig,
                                   String clarificationQuestionsSnapshot,
                                   String clarificationAnswersSnapshot,
                                   String assumptionsSnapshot) {
}
