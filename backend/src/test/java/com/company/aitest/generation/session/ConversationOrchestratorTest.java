package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import com.company.aitest.common.CurrentUser;
import org.junit.jupiter.api.Test;

class ConversationOrchestratorTest {

    @Test
    void generateCommandCannotBypassRequirementScopeReview() throws Exception {
        var sessionService = mock(GenerationSessionService.class);
        var messageService = mock(GenerationMessageService.class);
        var analysisService = mock(RequirementAnalysisService.class);
        var orchestrator = new ConversationOrchestrator(null, sessionService, messageService, analysisService, null, null);
        var now = LocalDateTime.of(2026, 7, 18, 10, 0);
        var session = new GenerationSessionRecord(10L, 20L, "需求", "ACTIVE", "WAITING_REQUIREMENT_SCOPE",
                1L, 2L, "", true, "PROJECT_AND_SYSTEM_TOM", 1, null, 7L, now, now);
        var analysis = analysis("NEED_SCOPE_CONFIRMATION", now);
        var user = new CurrentUser(7L, "tester", "USER");
        when(analysisService.getLatestAnalysis(10L)).thenReturn(analysis);
        when(messageService.appendAssistantMessage(any(), any(), nullable(String.class), any(), any(Integer.class)))
                .thenReturn(new GenerationMessageRecord(1L, 10L, "ASSISTANT", "提示", null, 1,
                        "OPERATION_HINT", now));

        Method method = ConversationOrchestrator.class.getDeclaredMethod(
                "handleSkipAndGenerate", GenerationSessionRecord.class, CurrentUser.class);
        method.setAccessible(true);
        method.invoke(orchestrator, session, user);

        verify(messageService).appendAssistantMessage(eq(10L), contains("不能跳过范围审核"),
                eq(null), eq("OPERATION_HINT"), eq(1));
        verify(analysisService, never()).skipAnalysis(any(), any(Integer.class));
        verify(analysisService, never()).doGenerate(any(), any());
    }

    @Test
    void generateCommandCannotBypassTestPointScopeReview() throws Exception {
        var sessionService = mock(GenerationSessionService.class);
        var messageService = mock(GenerationMessageService.class);
        var analysisService = mock(RequirementAnalysisService.class);
        var orchestrator = new ConversationOrchestrator(null, sessionService, messageService, analysisService, null, null);
        var now = LocalDateTime.of(2026, 7, 18, 10, 0);
        var session = new GenerationSessionRecord(10L, 20L, "需求", "ACTIVE", "WAITING_USER_CONFIRMATION",
                1L, 2L, "", true, "PROJECT_AND_SYSTEM_TOM", 1, null, 7L, now, now);
        var analysis = analysis("NEED_TEST_POINT_SCOPE_CONFIRMATION", now);
        var user = new CurrentUser(7L, "tester", "USER");
        when(analysisService.getLatestAnalysis(10L)).thenReturn(analysis);
        when(messageService.appendAssistantMessage(any(), any(), nullable(String.class), any(), any(Integer.class)))
                .thenReturn(new GenerationMessageRecord(1L, 10L, "ASSISTANT", "提示", null, 1,
                        "OPERATION_HINT", now));

        Method method = ConversationOrchestrator.class.getDeclaredMethod(
                "handleSkipAndGenerate", GenerationSessionRecord.class, CurrentUser.class);
        method.setAccessible(true);
        method.invoke(orchestrator, session, user);

        verify(messageService).appendAssistantMessage(eq(10L), contains("不能直接生成草稿"),
                eq(null), eq("OPERATION_HINT"), eq(1));
        verify(analysisService, never()).skipAnalysis(any(), any(Integer.class));
        verify(analysisService, never()).doGenerate(any(), any());
    }

    private static RequirementAnalysisRecord analysis(String status, LocalDateTime now) {
        return new RequirementAnalysisRecord(1L, 10L, 1, 0, "需求", "{}", null,
                "[]", null, "[]", "[]", null, null, null, status, now, now);
    }

    @Test
    void analysisOutputShowsReviewRiskQuestionsBeforeClarificationAndUncertain() throws Exception {
        var orchestrator = new ConversationOrchestrator(null, null, null, null, null, null);
        var now = LocalDateTime.of(2026, 6, 15, 21, 30);
        var analysis = new RequirementAnalysisRecord(
                1L, 10L, 2, 0,
                "测试需求",
                """
                {
                  "requirement_understanding": "理解需求",
                  "affected_modules": ["模块A"],
                  "review_risk_questions": [
                    {"question": "审批节点数量是否有限制？", "reason": "影响拆分", "impact": "需确认上限"}
                  ],
                  "uncertain_items": ["不确定项A"],
                  "clarification_questions": [
                    {"question": "入口在哪里？", "reason": "步骤", "impact": "页面覆盖"}
                  ]
                }
                """,
                null,
                """
                [
                  {"question": "入口在哪里？", "reason": "步骤", "impact": "页面覆盖"}
                ]
                """,
                null, "[]",
                "[{\"title\":\"测试点1\"}]",
                null, null, null,
                "NEED_CONFIRMATION", now, now
        );

        Method format = ConversationOrchestrator.class.getDeclaredMethod("formatAnalysisOutput", RequirementAnalysisRecord.class);
        format.setAccessible(true);
        String text = (String) format.invoke(orchestrator, analysis);

        // 验证输出顺序：影响范围 → 评审前需确认问题 → 需要澄清 → 分析不确定项 → 测试点分析
        assertTrue(text.contains("【影响范围】"));
        assertTrue(text.contains("【评审前需确认问题】"));
        assertTrue(text.contains("审批节点数量是否有限制"));
        assertTrue(text.contains("【需要澄清】"));
        assertTrue(text.contains("入口在哪里"));
        assertTrue(text.contains("【分析不确定项】"));
        assertTrue(text.contains("不确定项A"));
        assertTrue(text.contains("【测试点分析】"));

        // 验证顺序
        assertTrue(text.indexOf("【评审前需确认问题】") < text.indexOf("【需要澄清】"),
                "评审前需确认问题 应在 需要澄清 之前");
        assertTrue(text.indexOf("【需要澄清】") < text.indexOf("【分析不确定项】"),
                "需要澄清 应在 分析不确定项 之前");
        assertTrue(text.indexOf("【分析不确定项】") < text.indexOf("【测试点分析】"),
                "分析不确定项 应在 测试点分析 之前");
    }

    @Test
    void analysisOutputShowsClarificationQuestionsBeforeCaseGeneration() throws Exception {
        var orchestrator = new ConversationOrchestrator(null, null, null, null, null, null);
        var now = LocalDateTime.of(2026, 6, 15, 21, 30);
        var analysis = new RequirementAnalysisRecord(
                1L, 10L, 2, 0,
                "管理员配置禁止到访规则",
                """
                {
                  "requirement_understanding": "配置禁访规则",
                  "uncertain_items": ["TOM 上下文未匹配到具体配置入口"],
                  "clarification_questions": [
                    {
                      "question": "管理员配置禁访规则的入口在哪里？",
                      "reason": "交互入口决定测试步骤",
                      "impact": "影响页面和流程覆盖"
                    }
                  ]
                }
                """,
                null,
                """
                [
                  {
                    "question": "管理员配置禁访规则的入口在哪里？",
                    "reason": "交互入口决定测试步骤",
                    "impact": "影响页面和流程覆盖"
                  }
                ]
                """,
                null,
                "[]",
                "[{\"title\":\"配置禁访规则入口校验\"}]",
                null,
                null,
                null,
                "NEED_CONFIRMATION",
                now,
                now
        );

        Method format = ConversationOrchestrator.class.getDeclaredMethod("formatAnalysisOutput", RequirementAnalysisRecord.class);
        format.setAccessible(true);
        String text = (String) format.invoke(orchestrator, analysis);

        assertTrue(text.contains("【分析不确定项】"));
        assertTrue(text.contains("TOM 上下文未匹配到具体配置入口"));
        assertTrue(text.contains("【需要澄清】"));
        assertTrue(text.contains("管理员配置禁访规则的入口在哪里"));
        assertTrue(text.indexOf("【需要澄清】") < text.indexOf("【分析不确定项】"));
        assertTrue(text.indexOf("【需要澄清】") < text.indexOf("【测试点分析】"));

        Method prompt = ConversationOrchestrator.class.getDeclaredMethod("buildConfirmPrompt", RequirementAnalysisRecord.class);
        prompt.setAccessible(true);
        String confirmPrompt = (String) prompt.invoke(orchestrator, analysis);

        assertTrue(confirmPrompt.contains("请直接输入补充说明"));
        assertTrue(confirmPrompt.contains("重新分析"));
    }

    @Test
    void modelFailureMessageExplainsHttp524() throws Exception {
        var orchestrator = new ConversationOrchestrator(null, null, null, null, null, null);
        Method format = ConversationOrchestrator.class.getDeclaredMethod("formatModelFailure", String.class, Exception.class);
        format.setAccessible(true);

        String text = (String) format.invoke(orchestrator, "分析失败",
                new RuntimeException("需求分析失败: 模型调用失败，HTTP 524：error code: 524"));

        assertTrue(text.contains("HTTP 524"));
        assertTrue(text.contains("模型中转网关等待上游响应超时"));
        assertTrue(text.contains("短需求"));
        assertTrue(text.contains("更换 endpoint/模型"));
    }
}
