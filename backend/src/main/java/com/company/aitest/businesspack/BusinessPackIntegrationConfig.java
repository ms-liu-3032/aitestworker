package com.company.aitest.businesspack;

import com.company.aitest.semantic.ProjectSemanticContextService;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 将 BusinessPackService 注入到 ProjectSemanticContextService，
 * 实现 business_pack 与 semantic_pack 的联动刷新。
 */
@Configuration
public class BusinessPackIntegrationConfig {

    private final ProjectSemanticContextService semanticContextService;
    private final BusinessPackService businessPackService;

    public BusinessPackIntegrationConfig(ProjectSemanticContextService semanticContextService,
                                          BusinessPackService businessPackService) {
        this.semanticContextService = semanticContextService;
        this.businessPackService = businessPackService;
    }

    @PostConstruct
    public void wireServices() {
        semanticContextService.setBusinessPackService(businessPackService);
    }
}
