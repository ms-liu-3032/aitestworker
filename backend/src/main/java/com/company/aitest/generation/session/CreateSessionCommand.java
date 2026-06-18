package com.company.aitest.generation.session;

public record CreateSessionCommand(
        String sessionTitle,
        Long modelConfigId,
        Long promptTemplateId,
        boolean useMiniTom
) {}
