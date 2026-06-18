package com.company.aitest.workflow;

import java.util.Map;

import com.company.aitest.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class GenerationStateMachine {
    private static final Map<TaskStatus, Map<WorkflowEvent, TaskStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(TaskStatus.CREATED, Map.of(WorkflowEvent.START, TaskStatus.REQUIREMENT_ANALYZING,
                    WorkflowEvent.CANCEL, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.REQUIREMENT_ANALYZING, Map.of(WorkflowEvent.ANALYSIS_DONE, TaskStatus.RETRIEVAL_PLANNING,
                    WorkflowEvent.FAIL, TaskStatus.FAILED)),
            Map.entry(TaskStatus.RETRIEVAL_PLANNING, Map.of(WorkflowEvent.RETRIEVAL_PLAN_DONE, TaskStatus.KNOWLEDGE_RETRIEVING)),
            Map.entry(TaskStatus.KNOWLEDGE_RETRIEVING, Map.of(WorkflowEvent.KNOWLEDGE_RETRIEVED, TaskStatus.IMPACT_ANALYZING)),
            Map.entry(TaskStatus.IMPACT_ANALYZING, Map.of(WorkflowEvent.IMPACT_ANALYZED, TaskStatus.CLARIFYING_REQUIREMENT)),
            Map.entry(TaskStatus.CLARIFYING_REQUIREMENT, Map.of(WorkflowEvent.CLARIFICATION_ENDED, TaskStatus.TEST_POINT_GENERATING)),
            Map.entry(TaskStatus.TEST_POINT_GENERATING, Map.of(WorkflowEvent.TEST_POINTS_GENERATED, TaskStatus.TEST_POINT_REVIEWING)),
            Map.entry(TaskStatus.TEST_POINT_REVIEWING, Map.of(WorkflowEvent.TEST_POINTS_REVIEWED, TaskStatus.TEST_CASE_GENERATING)),
            Map.entry(TaskStatus.TEST_CASE_GENERATING, Map.of(WorkflowEvent.TEST_CASES_GENERATED, TaskStatus.TEST_CASE_REVIEWING)),
            Map.entry(TaskStatus.TEST_CASE_REVIEWING, Map.of(WorkflowEvent.TEST_CASES_REVIEWED, TaskStatus.QUALITY_CHECKING)),
            Map.entry(TaskStatus.QUALITY_CHECKING, Map.of(WorkflowEvent.QUALITY_CHECKED, TaskStatus.FINAL_CONFIRMING)),
            Map.entry(TaskStatus.FINAL_CONFIRMING, Map.of(WorkflowEvent.FINAL_CONFIRMED, TaskStatus.EXPORTING)),
            Map.entry(TaskStatus.EXPORTING, Map.of(WorkflowEvent.EXPORTED, TaskStatus.ASSET_PERSISTING)),
            Map.entry(TaskStatus.ASSET_PERSISTING, Map.of(WorkflowEvent.ASSET_PERSISTED, TaskStatus.COMPLETED))
    );

    public boolean canTransit(TaskStatus from, WorkflowEvent event) {
        return TRANSITIONS.getOrDefault(from, Map.of()).containsKey(event)
                || event == WorkflowEvent.CANCEL
                || event == WorkflowEvent.FAIL
                || event == WorkflowEvent.PAUSE;
    }

    public TaskStatus transit(TaskStatus from, WorkflowEvent event) {
        if (event == WorkflowEvent.CANCEL) {
            return TaskStatus.CANCELLED;
        }
        if (event == WorkflowEvent.FAIL) {
            return TaskStatus.FAILED;
        }
        if (event == WorkflowEvent.PAUSE) {
            return TaskStatus.PAUSED;
        }
        TaskStatus next = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (next == null) {
            throw new BusinessException("非法状态流转: " + from + " -> " + event);
        }
        return next;
    }
}
