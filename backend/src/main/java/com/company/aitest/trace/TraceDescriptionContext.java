package com.company.aitest.trace;

record TraceDescriptionContext(
        Long projectId,
        String target,
        String value,
        String objectLabel,
        String sectionTitle,
        String dialogTitle,
        String pageName
) {
}
