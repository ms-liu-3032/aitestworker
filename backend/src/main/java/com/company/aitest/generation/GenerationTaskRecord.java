package com.company.aitest.generation;

import java.time.LocalDateTime;

public record GenerationTaskRecord(Long id, Long projectId, String taskName, String requirementText,
                                   String requirementType, String currentStage, String status,
                                   String taskType,
                                   String runStatus, String requestHash, String errorCode, String errorMessage,
                                   Integer retryCount, LocalDateTime startedAt, LocalDateTime finishedAt,
                                   Long modelConfigId, Long promptTemplateId, Integer promptVersion, String promptSnapshot,
                                   Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                                   String generationMode, Boolean useMiniTom,
                                   String generationDepth,
                                   String miniTomContextSnapshot, String testScopeSnapshot,
                                   Integer tomHitCount,
                                   String projectTomSnapshot, String systemTomSnapshot,
                                   String tomWeightConfig,
                                   String clarificationQuestionsSnapshot,
                                   String clarificationAnswersSnapshot,
                                   String assumptionsSnapshot) {
    public GenerationTaskRecord(Long id, Long projectId, String taskName, String requirementText,
                                String requirementType, String currentStage, String status,
                                String taskType,
                                String runStatus, String requestHash, String errorCode, String errorMessage,
                                Integer retryCount, LocalDateTime startedAt, LocalDateTime finishedAt,
                                Long modelConfigId, Long promptTemplateId, Integer promptVersion, String promptSnapshot,
                                Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                                String generationMode, Boolean useMiniTom,
                                String miniTomContextSnapshot, String testScopeSnapshot,
                                Integer tomHitCount,
                                String projectTomSnapshot, String systemTomSnapshot,
                                String tomWeightConfig,
                                String clarificationQuestionsSnapshot,
                                String clarificationAnswersSnapshot,
                                String assumptionsSnapshot) {
        this(id, projectId, taskName, requirementText, requirementType, currentStage, status,
                taskType, runStatus, requestHash, errorCode, errorMessage, retryCount, startedAt,
                finishedAt, modelConfigId, promptTemplateId, promptVersion, promptSnapshot,
                createdBy, createdAt, updatedAt, generationMode, useMiniTom,
                GenerationDepth.defaultDepth().name(), miniTomContextSnapshot, testScopeSnapshot,
                tomHitCount, projectTomSnapshot, systemTomSnapshot, tomWeightConfig,
                clarificationQuestionsSnapshot, clarificationAnswersSnapshot, assumptionsSnapshot);
    }
}
