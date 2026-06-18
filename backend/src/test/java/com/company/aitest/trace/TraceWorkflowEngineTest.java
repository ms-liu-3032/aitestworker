package com.company.aitest.trace;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceWorkflowEngineTest {

    @Test
    void preTransitionPassThrough_shouldStillAllowMainTransitionToHandleSameStep() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“开始”按钮", "页面A", 1000L)
        );

        List<TraceWorkflowTransition<String>> pre = List.of(
                TraceWorkflowTransition.<String>of(
                        "prime",
                        (runtime, step, rule, desc, pageName) -> desc.contains("开始"),
                        (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.passThrough().setFlags("armed")
                )
        );
        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "handle",
                        (runtime, step, rule, desc, pageName) -> runtime.hasFlag("armed"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.addRewriteFromCurrent("已处理开始");
                            return TraceWorkflowOutcome.none();
                        }
                ).advanceAfter()
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", pre, main, List.of(), (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(1, result.size());
        assertEquals("已处理开始", result.get(0).description());
    }

    @Test
    void transitionConsumeTo_shouldSkipConsumedTailSteps() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("INPUT", "输入A", "页面A", 1000L),
                step("KEYDOWN", "按下回车键", "页面A", 1100L),
                step("INPUT", "输入AB", "页面A", 1200L),
                step("CLICK", "点击“提交”按钮", "页面A", 1300L)
        );

        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "mergeInput",
                        (runtime, step, rule, desc, pageName) -> "INPUT".equals(step.actionType()),
                        (runtime, step, rule, desc, pageName) -> {
                            int end = runtime.consumeFollowingWhile(next -> !"CLICK".equals(next.actionType()));
                            runtime.addRewrite("INPUT", "合并输入", runtime.sourceAt(end).relativeMs());
                            return TraceWorkflowOutcome.consumeTo(end);
                        }
                )
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", List.of(), main, List.of(), (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(2, result.size());
        assertEquals("合并输入", result.get(0).description());
        assertEquals("点击“提交”按钮", result.get(1).description());
    }

    @Test
    void postTransitionEndFlow_shouldStopFurtherProcessing() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“保存”按钮", "页面A", 1000L),
                step("SESSION_STOP", "结束本轮操作", "页面A", 2000L),
                step("CLICK", "点击“本不该被处理”按钮", "页面A", 3000L)
        );

        List<TraceWorkflowTransition<String>> post = List.of(
                TraceWorkflowTransition.<String>of(
                        "stop",
                        (runtime, step, rule, desc, pageName) -> "SESSION_STOP".equals(step.actionType()),
                        (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.endFlow()
                )
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", List.of(), List.of(), post, (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(2, result.size());
        assertEquals("点击“保存”按钮", result.get(0).description());
        assertEquals("结束本轮操作", result.get(1).description());
    }

    @Test
    void transitionOutcome_shouldCombineDynamicAndStaticFlagEffectsOnPassThrough() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“开始”按钮", "页面A", 1000L)
        );

        List<TraceWorkflowTransition<String>> pre = List.of(
                TraceWorkflowTransition.<String>of(
                        "prime",
                        (runtime, step, rule, desc, pageName) -> true,
                        (runtime, step, rule, desc, pageName) ->
                                TraceWorkflowOutcome.passThrough()
                                        .setFlags("armed")
                                        .clearFlags("stale")
                ).setFlagsAfter("afterPrime").clearFlagsAfter("legacy")
        );
        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "handle",
                        (runtime, step, rule, desc, pageName) ->
                                runtime.hasFlag("armed")
                                        && runtime.hasFlag("afterPrime")
                                        && !runtime.hasFlag("stale")
                                        && !runtime.hasFlag("legacy"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.addRewriteFromCurrent("命中所有 flag 副作用");
                            return TraceWorkflowOutcome.none();
                        }
                ).advanceAfter()
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", pre, main, List.of(), (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(1, result.size());
        assertEquals("命中所有 flag 副作用", result.get(0).description());
    }

    @Test
    void transitionJumpTo_shouldOverrideAdvanceAfterAndSkipIntermediateSteps() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“开始”按钮", "页面A", 1000L),
                step("CLICK", "点击“中间噪声”按钮", "页面A", 1100L),
                step("CLICK", "点击“目标”按钮", "页面A", 1200L)
        );

        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "skipNoise",
                        (runtime, step, rule, desc, pageName) -> desc.contains("开始"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.addRewriteFromCurrent("开始已处理");
                            return TraceWorkflowOutcome.jumpTo(2);
                        }
                ).advanceAfter()
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", List.of(), main, List.of(), (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(2, result.size());
        assertEquals("开始已处理", result.get(0).description());
        assertEquals("点击“目标”按钮", result.get(1).description());
    }

    @Test
    void preAndMainPassThrough_shouldStillReachDefaultAndPostTransitions() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“提交”按钮", "页面A", 1000L)
        );

        List<TraceWorkflowTransition<String>> pre = List.of(
                TraceWorkflowTransition.<String>of(
                        "prime",
                        (runtime, step, rule, desc, pageName) -> true,
                        (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.passThrough().setFlags("preArmed")
                )
        );
        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "markMain",
                        (runtime, step, rule, desc, pageName) -> runtime.hasFlag("preArmed"),
                        (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.passThrough().setFlags("mainSeen")
                )
        );
        List<TraceWorkflowTransition<String>> post = List.of(
                TraceWorkflowTransition.<String>of(
                        "postRewrite",
                        (runtime, step, rule, desc, pageName) -> runtime.hasFlag("mainSeen"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.removeLastResult();
                            runtime.addRewriteFromCurrent("默认动作后已被 post 改写");
                            return TraceWorkflowOutcome.none();
                        }
                ).advanceAfter()
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", pre, main, post, (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(1, result.size());
        assertEquals("默认动作后已被 post 改写", result.get(0).description());
    }

    @Test
    void endFlow_shouldWinOverJumpAndAdvanceAfter() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“结束”按钮", "页面A", 1000L),
                step("CLICK", "点击“本不该继续”按钮", "页面A", 1100L)
        );

        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "endImmediately",
                        (runtime, step, rule, desc, pageName) -> desc.contains("结束"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.addRewriteFromCurrent("流程主动结束");
                            return TraceWorkflowOutcome.endFlow().setFlags("ignored").clearFlags("ignoredToo");
                        }
                ).setFlagsAfter("afterEnd").clearFlagsAfter("afterClear").advanceAfter()
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", List.of(), main, List.of(), (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(1, result.size());
        assertEquals("流程主动结束", result.get(0).description());
    }

    @Test
    void endFlow_shouldSkipStaticAfterEffects() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“结束”按钮", "页面A", 1000L),
                step("CLICK", "点击“本不该继续”按钮", "页面A", 1100L)
        );

        List<TraceWorkflowTransition<String>> pre = List.of(
                TraceWorkflowTransition.<String>of(
                        "prime",
                        (runtime, step, rule, desc, pageName) -> true,
                        (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.passThrough().setFlags("armed")
                )
        );
        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "endImmediately",
                        (runtime, step, rule, desc, pageName) -> runtime.hasFlag("armed"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.addRewriteFromCurrent("流程主动结束");
                            return TraceWorkflowOutcome.endFlow().setFlags("dynamicIgnored");
                        }
                ).setFlagsAfter("afterEnd").advanceAfter()
        );
        List<TraceWorkflowTransition<String>> post = List.of(
                TraceWorkflowTransition.<String>of(
                        "shouldNotRun",
                        (runtime, step, rule, desc, pageName) ->
                                runtime.hasFlag("afterEnd") || runtime.hasFlag("dynamicIgnored"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.removeLastResult();
                            runtime.addRewriteFromCurrent("错误地命中了 after-effect");
                            return TraceWorkflowOutcome.none();
                        }
                ).advanceAfter()
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", pre, main, post, (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(1, result.size());
        assertEquals("流程主动结束", result.get(0).description());
    }

    @Test
    void noTransitionMatch_shouldPassThroughDefaultPathAndContinue() {
        List<TraceStepNormalizer.CleanTraceStep> steps = List.of(
                step("CLICK", "点击“普通按钮”", "页面A", 1000L),
                step("CLICK", "点击“第二个按钮”", "页面A", 1100L)
        );

        List<TraceWorkflowTransition<String>> pre = List.of(
                TraceWorkflowTransition.<String>of(
                        "neverMatchPre",
                        (runtime, step, rule, desc, pageName) -> false,
                        (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.passThrough()
                )
        );
        List<TraceWorkflowTransition<String>> main = List.of(
                TraceWorkflowTransition.<String>of(
                        "neverMatchMain",
                        (runtime, step, rule, desc, pageName) -> false,
                        (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.none()
                )
        );
        List<TraceWorkflowTransition<String>> post = List.of(
                TraceWorkflowTransition.<String>of(
                        "rewriteSecondOnly",
                        (runtime, step, rule, desc, pageName) -> desc.contains("第二个"),
                        (runtime, step, rule, desc, pageName) -> {
                            runtime.removeLastResult();
                            runtime.addRewriteFromCurrent("第二步被 post 改写");
                            return TraceWorkflowOutcome.none();
                        }
                ).advanceAfter()
        );

        List<TraceStepNormalizer.CleanTraceStep> result = TraceWorkflowEngine.run(
                steps, "rule", pre, main, post, (runtime, step) -> runtime.addCurrent()
        );

        assertEquals(2, result.size());
        assertEquals("点击“普通按钮”", result.get(0).description());
        assertEquals("第二步被 post 改写", result.get(1).description());
    }

    private TraceStepNormalizer.CleanTraceStep step(String actionType, String description, String pageName, long relativeMs) {
        return new TraceStepNormalizer.CleanTraceStep(1, "默认身份", actionType, description, pageName, "/test", relativeMs);
    }
}
