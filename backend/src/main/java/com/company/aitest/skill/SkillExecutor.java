package com.company.aitest.skill;

import java.time.Duration;
import java.time.LocalDateTime;

import com.company.aitest.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class SkillExecutor {
    private final SkillRegistry registry;
    private final SkillOutputValidator validator;
    private final ToolPolicy toolPolicy;
    private final SkillExecutionLogService logService;

    public SkillExecutor(SkillRegistry registry, SkillOutputValidator validator, ToolPolicy toolPolicy,
                         SkillExecutionLogService logService) {
        this.registry = registry;
        this.validator = validator;
        this.toolPolicy = toolPolicy;
        this.logService = logService;
    }

    @SuppressWarnings("unchecked")
    public <I, O> O execute(String skillName, I input, SkillExecutionContext context) {
        ControlledSkill<I, O> skill = (ControlledSkill<I, O>) registry.get(skillName);
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            O output = skill.execute(input, context);
            validator.validate(skillName, output);
            LocalDateTime finishedAt = LocalDateTime.now();
            logService.record(context.taskId(), context.projectId(), skillName, skill.stage(), String.valueOf(input),
                    String.valueOf(output), context.modelConfigId(), context.promptSnapshot(),
                    toolPolicy.allowedTools(skillName).toString(), "SUCCESS", null, null, startedAt, finishedAt,
                    Duration.between(startedAt, finishedAt).toMillis());
            return output;
        } catch (RuntimeException exception) {
            LocalDateTime finishedAt = LocalDateTime.now();
            logService.record(context.taskId(), context.projectId(), skillName, skill.stage(), String.valueOf(input),
                    null, context.modelConfigId(), context.promptSnapshot(), toolPolicy.allowedTools(skillName).toString(),
                    "FAILED", "SKILL_EXECUTION_FAILED", exception.getMessage(), startedAt, finishedAt,
                    Duration.between(startedAt, finishedAt).toMillis());
            if (exception instanceof BusinessException) {
                throw exception;
            }
            throw new BusinessException("Skill 执行失败: " + skillName);
        }
    }
}
