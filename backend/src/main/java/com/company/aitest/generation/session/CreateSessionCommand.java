package com.company.aitest.generation.session;

public record CreateSessionCommand(
        String sessionTitle,
        Long modelConfigId,
        Long promptTemplateId,
        boolean useMiniTom,
        String tomMode,
        String generationDepth
) {
    public CreateSessionCommand(String sessionTitle, Long modelConfigId, Long promptTemplateId,
                                boolean useMiniTom) {
        this(sessionTitle, modelConfigId, promptTemplateId, useMiniTom, null, null);
    }
}
