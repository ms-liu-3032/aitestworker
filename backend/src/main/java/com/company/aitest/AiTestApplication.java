package com.company.aitest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiTestApplication.class, args);
    }
}
