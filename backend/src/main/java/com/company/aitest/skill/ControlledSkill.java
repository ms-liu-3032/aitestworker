package com.company.aitest.skill;

public interface ControlledSkill<I, O> {
    String skillName();

    SkillStage stage();

    O execute(I input, SkillExecutionContext context);
}
