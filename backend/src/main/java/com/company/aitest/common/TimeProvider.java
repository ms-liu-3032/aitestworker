package com.company.aitest.common;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

@Component
public class TimeProvider {
    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
