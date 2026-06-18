package com.company.aitest.skill;

import com.company.aitest.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class SkillOutputValidator {
    public void validate(String skillName, Object output) {
        if (output == null) {
            throw new BusinessException("Skill 输出为空: " + skillName);
        }
    }
}
