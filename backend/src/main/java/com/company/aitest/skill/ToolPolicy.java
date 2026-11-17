package com.company.aitest.skill;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class ToolPolicy {
    private static final Map<String, List<String>> ALLOWED_TOOLS = Map.ofEntries(
            Map.entry("RequirementTypeDetectSkill", List.of("LLM_ADAPTER")),
            Map.entry("RetrievalKeywordExtractSkill", List.of("LLM_ADAPTER")),
            Map.entry("RetrievalStrategySelectSkill", List.of("LLM_ADAPTER")),
            Map.entry("YuqueKnowledgeRetrieveSkill", List.of("WEAVIATE_READ", "MYSQL_KNOWLEDGE_METADATA")),
            Map.entry("HistoricalCaseRetrieveSkill", List.of("MYSQL_READ", "WEAVIATE_READ")),
            Map.entry("GraphImpactAnalyzeSkill", List.of("NEO4J_READ")),
            Map.entry("ClarifyingQuestionGenerateSkill", List.of("LLM_ADAPTER")),
            Map.entry("TestPointGenerateSkill", List.of("LLM_ADAPTER")),
            Map.entry("TestCaseGenerateSkill", List.of("LLM_ADAPTER")),
            Map.entry("FeedbackOptimizeSkill", List.of("LLM_ADAPTER")),
            Map.entry("TestCaseQualityCheckSkill", List.of("RULE_ENGINE", "LLM_ADAPTER"))
    );

    public List<String> allowedTools(String skillName) {
        return ALLOWED_TOOLS.getOrDefault(skillName, List.of());
    }
}
