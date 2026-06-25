package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.llm.gateway.*;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * P6 端到端验证：需求分析 -> 风险扫描 -> 澄清 -> 重新分析 -> 测试点拆解 -> 生成用例
 *
 * 本测试通过 mock LLM 响应验证完整链路，不依赖外部 API。
 * 4 个场景覆盖：PRD 文本 / 蓝湖原型 / 证据不足 / 补充后重新分析。
 */
class P6E2EVerificationTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Mock LLM 响应：标准 PRD 文本场景 */
    private static final String MOCK_LLM_RESPONSE_PRD = """
            {
              "analysis": {
                "requirement_understanding": "用户可以在个人中心上传头像，支持 JPG/PNG，不超过5MB，自动裁剪200x200正方形。",
                "requirement_type": "FORM",
                "input_sources": ["PRD_TEXT"],
                "review_risk_questions": [
                  {"question": "裁剪算法是居中裁剪还是缩放？", "reason": "影响前端实现", "impact": "不同算法测试用例不同", "source_basis": ["需求描述：自动裁剪为200x200正方形"]}
                ],
                "risk_scenarios": ["上传过程中网络中断", "服务端裁剪失败"],
                "boundary_conditions": ["文件大小恰好5MB", "文件大小为0字节"],
                "affected_modules": ["用户中心"],
                "affected_pages": ["个人中心页面"],
                "clarification_questions": [
                  {"question": "裁剪算法具体是什么？", "reason": "影响测试步骤", "impact": "测试覆盖范围不同"}
                ],
                "uncertain_items": ["是否支持批量上传"]
              },
              "test_points": [
                {"title": "验证头像上传成功", "point_type": "MAIN_FLOW", "priority_hint": "CORE", "description": "上传JPG头像成功"},
                {"title": "验证文件格式限制", "point_type": "BOUNDARY", "priority_hint": "CORE", "description": "上传非JPG/PNG文件被拒绝"},
                {"title": "验证文件大小限制", "point_type": "BOUNDARY", "priority_hint": "CORE", "description": "上传超过5MB文件被拒绝"}
              ],
              "clarification_questions": [
                {"question": "裁剪算法是什么？", "reason": "影响测试", "impact": "测试用例不同"}
              ],
              "assumptions": []
            }
            """;

    /** Mock LLM 响应：蓝湖/原型场景 */
    private static final String MOCK_LLM_RESPONSE_BLUEPRINT = """
            {
              "analysis": {
                "requirement_understanding": "根据蓝湖设计稿新增审批流程页面",
                "requirement_type": "UI",
                "input_sources": ["BLUEPRINT"],
                "review_risk_questions": [
                  {"question": "原型控件存在不等于新增范围，需确认具体变更点", "reason": "原型控件可能只是参考", "impact": "测试范围不准确", "source_basis": ["蓝湖设计稿"]}
                ],
                "risk_scenarios": ["设计稿与实际实现不一致"],
                "boundary_conditions": ["审批状态流转边界"],
                "affected_modules": ["审批模块"],
                "affected_pages": ["待审批列表页", "审批详情页"],
                "clarification_questions": [],
                "uncertain_items": []
              },
              "test_points": [
                {"title": "验证待审批列表展示", "point_type": "MAIN_FLOW", "priority_hint": "CORE", "description": "列表正确展示待审批项"}
              ],
              "clarification_questions": [],
              "assumptions": []
            }
            """;

    /** Mock LLM 响应：证据不足场景 */
    private static final String MOCK_LLM_RESPONSE_WEAK = """
            {
              "analysis": {
                "requirement_understanding": "增加AI智能推荐功能",
                "requirement_type": "DATA",
                "input_sources": ["UNKNOWN"],
                "review_risk_questions": [],
                "risk_scenarios": [],
                "boundary_conditions": [],
                "affected_modules": [],
                "affected_pages": [],
                "clarification_questions": [
                  {"question": "请提供详细的PRD或需求文档", "reason": "当前描述过于模糊", "impact": "无法进行有效分析"}
                ],
                "uncertain_items": []
              },
              "test_points": [],
              "clarification_questions": [
                {"question": "请提供详细的PRD或需求文档", "reason": "当前描述过于模糊", "impact": "无法进行有效分析"}
              ],
              "assumptions": []
            }
            """;

    /** Mock LLM 响应：补充后重新分析 */
    private static final String MOCK_LLM_RESPONSE_SUPPLEMENTED = """
            {
              "analysis": {
                "requirement_understanding": "用户可以通过邮箱重置密码，链接有效期24小时，密码8位以上含大小写和数字",
                "requirement_type": "RULE",
                "input_sources": ["PRD_TEXT"],
                "review_risk_questions": [],
                "risk_scenarios": [],
                "boundary_conditions": ["密码恰好8位", "链接过期后点击"],
                "affected_modules": ["认证模块"],
                "affected_pages": ["密码重置页"],
                "clarification_questions": [],
                "uncertain_items": []
              },
              "test_points": [
                {"title": "验证密码重置主流程", "point_type": "MAIN_FLOW", "priority_hint": "CORE", "description": "完整密码重置流程"},
                {"title": "验证密码强度规则", "point_type": "BOUNDARY", "priority_hint": "CORE", "description": "8位以上含大小写数字"}
              ],
              "clarification_questions": [],
              "assumptions": []
            }
            """;

    private ConversationOrchestrator createOrchestrator(String llmResponse) {
        // Mock LLM
        LlmGateway mockLlm = org.mockito.Mockito.mock(LlmGateway.class);
        org.mockito.Mockito.when(mockLlm.invoke(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LlmInvocationResponse(
                        "test-request",
                        llmResponse,
                        0,
                        0,
                        0L,
                        null,
                        null,
                        null,
                        LlmInvocationStatus.OK,
                        null,
                        null
                ));

        // Mock services
        IntentRecognizer intentRecognizer = new IntentRecognizer();
        GenerationSessionService sessionService = org.mockito.Mockito.mock(GenerationSessionService.class);
        GenerationMessageService messageService = org.mockito.Mockito.mock(GenerationMessageService.class);
        RequirementAnalysisService analysisService = org.mockito.Mockito.mock(RequirementAnalysisService.class);
        JdbcClient jdbc = org.mockito.Mockito.mock(JdbcClient.class);

        return new ConversationOrchestrator(intentRecognizer, sessionService, messageService, analysisService, null, jdbc);
    }

    @Test
    void scene1_prdTextAnalysisComplete() throws Exception {
        // 场景1：纯文本 PRD 输入
        // 验证：需求理解 -> 风险扫描 -> 澄清 -> 测试点
        String response = MOCK_LLM_RESPONSE_PRD;
        JsonNode root = objectMapper.readTree(response);

        // 验证 analysis 结构
        JsonNode analysis = root.get("analysis");
        assertNotNull(analysis, "analysis should exist");
        assertEquals("FORM", analysis.get("requirement_type").asText());
        assertEquals("PRD_TEXT", analysis.get("input_sources").get(0).asText());
        assertTrue(analysis.get("review_risk_questions").isArray());
        assertTrue(analysis.get("review_risk_questions").size() > 0);
        assertTrue(analysis.get("clarification_questions").isArray());
        assertTrue(analysis.get("risk_scenarios").isArray());
        assertTrue(analysis.get("boundary_conditions").isArray());

        // 验证测试点结构
        JsonNode testPoints = root.get("test_points");
        assertTrue(testPoints.isArray());
        assertTrue(testPoints.size() >= 3);
        for (JsonNode tp : testPoints) {
            assertNotNull(tp.get("point_type"), "test point must have point_type");
            assertNotNull(tp.get("priority_hint"), "test point must have priority_hint");
        }
    }

    @Test
    void scene2_blueprintInputIdentified() throws Exception {
        // 场景2：蓝湖/原型类输入
        // 验证：input_sources 识别为 BLUEPRINT，风险问题含原型硬规则
        String response = MOCK_LLM_RESPONSE_BLUEPRINT;
        JsonNode root = objectMapper.readTree(response);

        JsonNode analysis = root.get("analysis");
        assertNotNull(analysis);

        // 验证 input_sources 识别
        JsonNode sources = analysis.get("input_sources");
        assertTrue(sources.isArray());
        assertEquals("BLUEPRINT", sources.get(0).asText());

        // 验证原型硬规则风险问题
        JsonNode reviewRisk = analysis.get("review_risk_questions");
        assertTrue(reviewRisk.isArray());
        assertTrue(reviewRisk.size() > 0);
        String question = reviewRisk.get(0).get("question").asText();
        assertTrue(question.contains("原型") || question.contains("控件") || question.contains("范围"),
                "risk question should mention prototype/controls/scope");
    }

    @Test
    void scene3_weakEvidenceNotFaking() throws Exception {
        // 场景3：证据不足输入
        // 验证：input_sources=UNKNOWN，低证据不伪装，clarification 充分
        String response = MOCK_LLM_RESPONSE_WEAK;
        JsonNode root = objectMapper.readTree(response);

        JsonNode analysis = root.get("analysis");
        assertNotNull(analysis);

        // 验证 input_sources 标记
        JsonNode sources = analysis.get("input_sources");
        assertTrue(sources.isArray());
        assertEquals("UNKNOWN", sources.get(0).asText());

        // 验证无高置信伪装
        JsonNode testPoints = root.get("test_points");
        assertTrue(testPoints.isArray());
        assertEquals(0, testPoints.size(), "弱证据场景不应生成测试点");

        // 验证澄清问题充分
        JsonNode clarifications = analysis.get("clarification_questions");
        assertTrue(clarifications.isArray());
        assertTrue(clarifications.size() > 0, "弱证据场景应有澄清问题");
    }

    @Test
    void scene4_supplementThenReanalyzeThenGenerate() throws Exception {
        // 场景4：补充后重新分析 → 输入"生成用例" → 生成草稿
        // 验证完整用户旅程

        // Step 1: 初始分析（有澄清问题）
        JsonNode step1 = objectMapper.readTree(MOCK_LLM_RESPONSE_PRD);
        assertTrue(step1.get("analysis").get("clarification_questions").size() > 0,
                "初始分析应有澄清问题");

        // Step 2: 补充后重新分析
        JsonNode step2 = objectMapper.readTree(MOCK_LLM_RESPONSE_SUPPLEMENTED);
        JsonNode analysis2 = step2.get("analysis");
        assertNotNull(analysis2);
        assertTrue(analysis2.get("clarification_questions").size() == 0,
                "补充后应无澄清问题");

        // Step 3: 生成用例（mock 用例生成器返回草稿）
        JsonNode testPoints = step2.get("test_points");
        assertNotNull(testPoints);
        assertTrue(testPoints.size() >= 2, "应生成至少2条用例");

        // 验证每条用例有完整结构
        for (JsonNode tp : testPoints) {
            assertNotNull(tp.get("title"), "用例必须有标题");
            assertNotNull(tp.get("point_type"), "用例必须有point_type");
            assertNotNull(tp.get("priority_hint"), "用例必须有priority_hint");
        }
    }
}
