package com.company.aitest.tools;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {
    private final Map<String, TestTool> tools;

    public ToolRegistry(List<TestTool> tools) {
        this.tools = tools.stream().collect(Collectors.toMap(TestTool::toolCode, Function.identity()));
    }

    public TestTool get(String toolCode) {
        TestTool tool = tools.get(toolCode);
        if (tool == null) {
            throw new BusinessException("未知测试工具: " + toolCode);
        }
        return tool;
    }
}
