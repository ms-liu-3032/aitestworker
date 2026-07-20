package com.company.aitest.generation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.company.aitest.generation.session.GenerationSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AsyncGenerationTaskRecoveryTest {

    @Test
    void marksTasksInterruptedByRestartAsRetryableAndRestoresLinkedSessions() {
        GenerationTaskService taskService = Mockito.mock(GenerationTaskService.class);
        GenerationSessionService sessionService = Mockito.mock(GenerationSessionService.class);
        when(taskService.failInterruptedTasks()).thenReturn(List.of(12L, 13L));

        new AsyncGenerationTaskRecovery(taskService, sessionService).recoverInterruptedTasks();

        verify(taskService).failInterruptedTasks();
        verify(sessionService).restoreInterruptedExecutionTasks(List.of(12L, 13L));
    }
}
