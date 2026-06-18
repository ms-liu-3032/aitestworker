package com.company.aitest.skill;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.company.aitest.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {
    private final Map<String, ControlledSkill<?, ?>> skills;

    public SkillRegistry(List<ControlledSkill<?, ?>> skills) {
        this.skills = skills.stream().collect(Collectors.toMap(ControlledSkill::skillName, Function.identity()));
    }

    public ControlledSkill<?, ?> get(String skillName) {
        ControlledSkill<?, ?> skill = skills.get(skillName);
        if (skill == null) {
            throw new BusinessException("Skill 未注册: " + skillName);
        }
        return skill;
    }
}
