package com.company.aitest.wiki;

public record WikiEntryRelationRecord(
        Long id,
        Long entryId,
        Long relatedTomId,
        Long relatedBusinessPackId,
        String relationType
) {}
