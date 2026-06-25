package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ConversationOrchestratorTest {

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
}
