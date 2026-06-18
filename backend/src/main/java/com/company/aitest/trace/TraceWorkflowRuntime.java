package com.company.aitest.trace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class TraceWorkflowRuntime {

    private final List<TraceStepNormalizer.CleanTraceStep> source;
    private final List<TraceStepNormalizer.CleanTraceStep> result = new ArrayList<>();
    private final Set<String> flags = new LinkedHashSet<>();
    private int index = 0;

    TraceWorkflowRuntime(List<TraceStepNormalizer.CleanTraceStep> source) {
        this.source = source;
    }

    boolean hasCurrent() {
        return index < source.size();
    }

    TraceStepNormalizer.CleanTraceStep current() {
        return source.get(index);
    }

    int index() {
        return index;
    }

    List<TraceStepNormalizer.CleanTraceStep> source() {
        return source;
    }

    TraceStepNormalizer.CleanTraceStep sourceAt(int targetIndex) {
        return source.get(targetIndex);
    }

    List<TraceStepNormalizer.CleanTraceStep> result() {
        return result;
    }

    void next() {
        index++;
    }

    void jumpTo(int nextIndex) {
        this.index = nextIndex;
    }

    void end() {
        this.index = source.size();
    }

    boolean hasAhead(int startOffset, int distance, Predicate<TraceStepNormalizer.CleanTraceStep> predicate) {
        int start = Math.max(0, index + startOffset);
        int end = Math.min(source.size(), start + distance);
        for (int i = start; i < end; i++) {
            if (predicate.test(source.get(i))) {
                return true;
            }
        }
        return false;
    }

    int consumeFollowingWhile(Predicate<TraceStepNormalizer.CleanTraceStep> predicate) {
        int cursor = index;
        while (cursor + 1 < source.size() && predicate.test(source.get(cursor + 1))) {
            cursor++;
        }
        return cursor;
    }

    void addCurrent() {
        result.add(current());
    }

    void addStep(TraceStepNormalizer.CleanTraceStep step) {
        result.add(step);
    }

    void addRewrite(String actionType, String description, long relativeMs) {
        TraceStepNormalizer.CleanTraceStep step = current();
        result.add(new TraceStepNormalizer.CleanTraceStep(
                0,
                step.actor(),
                actionType,
                description,
                step.pageName(),
                step.pageUrl(),
                relativeMs
        ));
    }

    void addRewriteFromCurrent(String description) {
        TraceStepNormalizer.CleanTraceStep step = current();
        addRewrite(step.actionType(), description, step.relativeMs());
    }

    TraceStepNormalizer.CleanTraceStep lastResult() {
        return result.isEmpty() ? null : result.get(result.size() - 1);
    }

    boolean hasLastResult() {
        return !result.isEmpty();
    }

    void removeLastResult() {
        if (!result.isEmpty()) {
            result.remove(result.size() - 1);
        }
    }

    boolean hasRecentResult(int limit, Predicate<TraceStepNormalizer.CleanTraceStep> predicate) {
        if (result.isEmpty()) {
            return false;
        }
        int start = Math.max(0, result.size() - Math.max(limit, 1));
        for (int i = result.size() - 1; i >= start; i--) {
            if (predicate.test(result.get(i))) {
                return true;
            }
        }
        return false;
    }

    void setFlag(String flag) {
        if (flag != null && !flag.isBlank()) {
            flags.add(flag);
        }
    }

    void clearFlag(String flag) {
        flags.remove(flag);
    }

    boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    void clearFlags(String... toClear) {
        for (String flag : toClear) {
            clearFlag(flag);
        }
    }
}
