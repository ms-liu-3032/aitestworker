package com.company.aitest.common;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 统一状态中文映射服务。
 * <p>
 * 所有状态的中文标签在此集中管理，避免各处硬编码。
 */
@Component
public class StatusLabelService {

    // 通用资产状态
    private static final Map<String, String> STATUS_LABELS = Map.ofEntries(
            Map.entry("DRAFT", "草稿"),
            Map.entry("CANDIDATE", "候选"),
            Map.entry("REVIEWING", "待审核"),
            Map.entry("ACTIVE", "已生效"),
            Map.entry("CONFIRMED", "已确认"),
            Map.entry("REJECTED", "已驳回"),
            Map.entry("DEPRECATED", "已弃用"),
            Map.entry("ARCHIVED", "已归档"),
            Map.entry("FAILED", "失败"),
            Map.entry("NEED_REPAIR", "需修复"),
            Map.entry("PENDING", "等待中"),
            Map.entry("RUNNING", "处理中"),
            Map.entry("SUCCESS", "已完成"),
            Map.entry("CANCELED", "已取消"),
            Map.entry("RETRYING", "重试中"),
            Map.entry("TIMEOUT", "已超时"),
            Map.entry("GENERATED", "已生成"),
            Map.entry("SUBMITTED", "已提交"),
            Map.entry("RECORDING", "录制中"),
            Map.entry("STOPPED", "已停止"),
            Map.entry("PROCESSING", "处理中"),
            Map.entry("SUMMARIZED", "已生成摘要")
    );

    // 有效性标签
    private static final Map<String, String> VALIDITY_LABELS = Map.ofEntries(
            Map.entry("STANDARD", "标准有效"),
            Map.entry("HIGH", "高置信"),
            Map.entry("MEDIUM", "中置信"),
            Map.entry("LOW", "低置信"),
            Map.entry("PERSONAL_ONLY", "仅个人有效"),
            Map.entry("TASK_ONLY", "仅本次任务有效"),
            Map.entry("BUG_REPRO", "缺陷复现"),
            Map.entry("DEMO", "演示数据"),
            Map.entry("DEBUG_DATA", "调试数据"),
            Map.entry("DIRTY_DATA", "脏数据"),
            Map.entry("OLD_VERSION", "旧版本"),
            Map.entry("TO_CONFIRM", "待确认")
    );

    // 来源类型
    private static final Map<String, String> SOURCE_TYPE_LABELS = Map.ofEntries(
            Map.entry("TRACE_SUMMARY", "轨迹摘要"),
            Map.entry("TRACE_EVENT", "轨迹事件"),
            Map.entry("MANUAL", "使用手册"),
            Map.entry("MANUAL_SECTION", "手册片段"),
            Map.entry("MANUAL_IMPORT", "手册导入"),
            Map.entry("FORMAL_CASE", "正式用例"),
            Map.entry("CASE_DRAFT", "用例草稿"),
            Map.entry("USER_INPUT", "用户输入"),
            Map.entry("AI_GENERATED", "AI 生成"),
            Map.entry("HUMAN_CREATED", "人工创建"),
            Map.entry("SYSTEM_IMPORT", "系统导入"),
            Map.entry("PATTERN", "模式学习")
    );

    // 模型类型
    private static final Map<String, String> MODEL_TYPE_LABELS = Map.ofEntries(
            Map.entry("MODULE", "模块"),
            Map.entry("PAGE", "页面"),
            Map.entry("FIELD", "字段"),
            Map.entry("ROLE", "角色"),
            Map.entry("ACTION", "操作"),
            Map.entry("FLOW", "流程"),
            Map.entry("STATE", "状态"),
            Map.entry("ASSERTION", "断言")
    );

    // 摘要范围
    private static final Map<String, String> SUMMARY_SCOPE_LABELS = Map.ofEntries(
            Map.entry("GROUP", "轨迹组"),
            Map.entry("SESSION", "会话"),
            Map.entry("ISSUE_CLIP", "问题片段")
    );

    // 优先级
    private static final Map<String, String> PRIORITY_LABELS = Map.ofEntries(
            Map.entry("P0", "P0-阻塞"),
            Map.entry("P1", "P1-严重"),
            Map.entry("P2", "P2-一般"),
            Map.entry("P3", "P3-轻微"),
            Map.entry("P4", "P4-建议")
    );

    public String statusLabel(String status) {
        if (status == null) return "";
        return STATUS_LABELS.getOrDefault(status, status);
    }

    public String validityLabel(String label) {
        if (label == null) return "";
        return VALIDITY_LABELS.getOrDefault(label, label);
    }

    public String sourceTypeLabel(String sourceType) {
        if (sourceType == null) return "";
        return SOURCE_TYPE_LABELS.getOrDefault(sourceType, sourceType);
    }

    public String modelTypeLabel(String modelType) {
        if (modelType == null) return "";
        return MODEL_TYPE_LABELS.getOrDefault(modelType, modelType);
    }

    public String summaryScopeLabel(String scope) {
        if (scope == null) return "";
        return SUMMARY_SCOPE_LABELS.getOrDefault(scope, scope);
    }

    public String priorityLabel(String priority) {
        if (priority == null) return "";
        return PRIORITY_LABELS.getOrDefault(priority, priority);
    }
}
