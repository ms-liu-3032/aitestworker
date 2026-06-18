package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ConversationOrchestratorTest {

    @Test
    void analysisOutputShowsClarificationQuestionsBeforeCaseGeneration() throws Exception {
        var orchestrator = new ConversationOrchestrator(null, null, null, null, null, null);
        var now = LocalDateTime.of(2026, 6, 15, 21, 30);
        var analysis = new RequirementAnalysisRecord(
                1L,
                10L,
                2,
                0,
                "管理员配置访问控制规则",
                """
                {
                  "requirement_understanding": "配置访问控制规则",
                  "uncertain_items": ["TOM 上下文未匹配到具体配置入口"],
                  "clarification_questions": [
                    {
                      "question": "管理员配置访问控制规则的入口在哪里？",
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
                    "question": "管理员配置访问控制规则的入口在哪里？",
                    "reason": "交互入口决定测试步骤",
                    "impact": "影响页面和流程覆盖"
                  }
                ]
                """,
                null,
                "[]",
                "[{\"title\":\"配置访问控制规则入口校验\"}]",
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
        assertTrue(text.contains("管理员配置访问控制规则的入口在哪里"));
        assertTrue(text.indexOf("【需要澄清】") < text.indexOf("【分析不确定项】"));
        assertTrue(text.indexOf("【需要澄清】") < text.indexOf("【测试点分析】"));

        Method prompt = ConversationOrchestrator.class.getDeclaredMethod("buildConfirmPrompt", RequirementAnalysisRecord.class);
        prompt.setAccessible(true);
        String confirmPrompt = (String) prompt.invoke(orchestrator, analysis);

        assertTrue(confirmPrompt.contains("请直接输入补充说明"));
        assertTrue(confirmPrompt.contains("重新分析"));
    }
}
