package com.company.aitest.generation;

import com.company.aitest.generation.session.GenerationSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Reconciles tasks whose in-memory execution was lost during a process restart. */
@Component
public class AsyncGenerationTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(AsyncGenerationTaskRecovery.class);

    private final GenerationTaskService taskService;
    private final GenerationSessionService sessionService;

    public AsyncGenerationTaskRecovery(GenerationTaskService taskService,
                                       GenerationSessionService sessionService) {
        this.taskService = taskService;
        this.sessionService = sessionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        var taskIds = taskService.failInterruptedTasks();
        sessionService.restoreInterruptedExecutionTasks(taskIds);
        if (!taskIds.isEmpty()) {
            log.warn("Marked {} interrupted async generation task(s) as retryable after application restart", taskIds.size());
        }
    }
}
