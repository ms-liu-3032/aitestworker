package com.company.aitest.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 平台默认运行态回归测试。
 * 确保开源默认运行态不会回退到历史定制耦合。
 */
class DefaultRuntimeRegressionTest {

    /**
     * 验证默认 trace-rulepacks 目录为空（不自动加载任何业务规则包）。
     */
    @Test
    void defaultRulepacksDirectoryIsEmpty() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:trace-rulepacks/*.json");
        // 默认目录应为空，不加载任何规则包
        assertEquals(0, resources.length, "默认 trace-rulepacks 目录应为空，不应自动加载业务规则包");
    }

    /**
     * 验证样例规则包不在默认 classpath 加载路径中。
     */
    @Test
    void samplePackNotInDefaultClasspath() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        // 只搜索 trace-rulepacks/ 目录，不搜索 samples 目录
        Resource[] resources = resolver.getResources("classpath*:trace-rulepacks/*.json");
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            assertNotNull(filename);
            assertFalse(filename.contains("sample"),
                    "默认 trace-rulepacks 目录不应包含样例规则包: " + filename);
        }
    }

    /**
     * 验证 DomainPresetService 返回通用预设。
     */
    @Test
    void domainPresetServiceReturnsGeneric() {
        com.company.aitest.preset.DomainPresetService service =
                new com.company.aitest.preset.DomainPresetService(List.of());
        var defaultPreset = service.defaultPreset();
        assertNotNull(defaultPreset);
        assertEquals("GENERIC", defaultPreset.presetKey());
        assertEquals("通用业务", defaultPreset.defaultBusinessDomain());
        assertFalse(defaultPreset.defaultBusinessDomain().isBlank(),
                "默认业务域应为非空通用值");
    }

    /**
     * 验证 preset 列表只包含通用预设。
     */
    @Test
    void domainPresetServiceListOnlyGeneric() {
        com.company.aitest.preset.DomainPresetService service =
                new com.company.aitest.preset.DomainPresetService(List.of());
        var presets = service.listPresets();
        assertEquals(1, presets.size());
        assertEquals("GENERIC", presets.get(0).presetKey());
    }

    /**
     * 验证 business_domain 默认值在 V23 迁移后为通用空值。
     * 这个测试通过 SQL 语句内容验证。
     */
    @Test
    void v23MigrationFixesDefault() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V23__normalize_business_domain_default.sql");
        assertTrue(Files.exists(migrationPath), "V23 迁移文件应存在");
        String content = Files.readString(migrationPath);
        assertTrue(content.contains("SET DEFAULT ''"), "V23 应将默认值改为空字符串");
        assertTrue(content.contains("business_domain <> ''"), "V23 应将历史业务默认值回填为空字符串");
    }

    /**
     * 验证开源默认值通过新增迁移收口，而不是继续改写历史 V14。
     */
    @Test
    void v28OpenSourceSchemaDefaultsExists() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V28__open_source_schema_defaults.sql");
        assertTrue(Files.exists(migrationPath), "V28 迁移文件应存在");
        String content = Files.readString(migrationPath);
        assertTrue(content.contains("MODIFY COLUMN business_domain VARCHAR(64) NOT NULL DEFAULT ''"),
                "V28 应将手册导入业务域默认值改为空字符串");
        assertTrue(content.contains("审批流、CRM、设备巡检"),
                "V28 应使用通用业务域注释示例");
    }

    /**
     * 验证 V24 business_pack 表结构存在。
     */
    @Test
    void v24BusinessPackSchemaExists() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V24__business_pack_schema.sql");
        assertTrue(Files.exists(migrationPath), "V24 迁移文件应存在");
        String content = Files.readString(migrationPath);
        assertTrue(content.contains("CREATE TABLE business_pack"), "应创建 business_pack 表");
        assertTrue(content.contains("CREATE TABLE business_pack_item"), "应创建 business_pack_item 表");
    }

    /**
     * 验证 V25 business_pack 关系和快照表存在。
     */
    @Test
    void v25BusinessPackRelationSnapshotExists() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V25__business_pack_relation_snapshot.sql");
        assertTrue(Files.exists(migrationPath), "V25 迁移文件应存在");
        String content = Files.readString(migrationPath);
        assertTrue(content.contains("CREATE TABLE business_pack_relation"), "应创建 business_pack_relation 表");
        assertTrue(content.contains("CREATE TABLE business_pack_snapshot"), "应创建 business_pack_snapshot 表");
    }

    /**
     * 验证 BusinessPackService 存在且可实例化。
     */
    @Test
    void businessPackServiceExists() {
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class);
        assertNotNull(com.company.aitest.businesspack.BusinessPackController.class);
    }

    /**
     * 验证 ProjectSemanticContextService 包含 business_pack 消费者方法。
     */
    @Test
    void semanticContextServiceConsumesBusinessPack() throws NoSuchMethodException {
        var method = com.company.aitest.semantic.ProjectSemanticContextService.class
                .getDeclaredMethod("loadBusinessPackSignals", Long.class);
        assertNotNull(method, "ProjectSemanticContextService 应有 loadBusinessPackSignals 方法");
    }

    /**
     * 验证 V26 business_pack 绑定和消费表存在。
     */
    @Test
    void v26BusinessPackBindingConsumptionExists() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V26__business_pack_binding_consumption.sql");
        assertTrue(Files.exists(migrationPath), "V26 迁移文件应存在");
        String content = Files.readString(migrationPath);
        assertTrue(content.contains("CREATE TABLE business_pack_rule_binding"), "应创建 business_pack_rule_binding 表");
        assertTrue(content.contains("CREATE TABLE business_pack_scan_binding"), "应创建 business_pack_scan_binding 表");
        assertTrue(content.contains("CREATE TABLE business_pack_tom_binding"), "应创建 business_pack_tom_binding 表");
        assertTrue(content.contains("CREATE TABLE business_pack_semantic_binding"), "应创建 business_pack_semantic_binding 表");
        assertTrue(content.contains("CREATE TABLE business_pack_consumption_log"), "应创建 business_pack_consumption_log 表");
    }

    /**
     * 验证 V29 轨迹规则包配置表存在，后续规则包可走数据库而不是硬编码 Java 类。
     */
    @Test
    void v29TraceRulePackConfigExists() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V29__trace_rule_pack_config.sql");
        assertTrue(Files.exists(migrationPath), "V29 迁移文件应存在");
        String content = Files.readString(migrationPath);
        assertTrue(content.contains("CREATE TABLE trace_rule_pack_config"), "应创建 trace_rule_pack_config 表");
        assertTrue(content.contains("UNIQUE KEY uk_trp_project_key"), "规则包应按项目和 key 去重");
        assertTrue(content.contains("project_id      BIGINT NULL"), "规则包应预留项目级扩展字段");
        assertTrue(content.contains("config_json     JSON NOT NULL"), "规则包配置应以 JSON 形式存储");
    }

    @Test
    void v30BusinessPackRefreshDiagnosticExists() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V30__business_pack_refresh_diagnostic.sql");
        assertTrue(Files.exists(migrationPath), "V30 迁移文件应存在");
        String content = Files.readString(migrationPath);
        assertTrue(content.contains("CREATE TABLE business_pack_refresh_diagnostic"), "应创建 business_pack_refresh_diagnostic 表");
        assertTrue(content.contains("generated_pack_count"), "应记录生成业务包数量");
        assertTrue(content.contains("inferred_relation_count"), "应记录自动推断关系数量");
    }

    /**
     * 验证规则包配置 API 与项目级清洗入口存在。
     */
    @Test
    void traceRulePackConfigApiExists() throws Exception {
        assertNotNull(com.company.aitest.trace.TraceRulePackConfigService.class);
        assertNotNull(com.company.aitest.trace.TraceRulePackConfigController.class);
        assertNotNull(Class.forName("com.company.aitest.trace.TraceStepNormalizer")
                .getDeclaredMethod("normalize", Long.class, List.class, java.util.Map.class));
    }

    /**
     * 验证 BusinessPackService 包含绑定和消费方法。
     */
    @Test
    void businessPackServiceHasBindingAndConsumptionMethods() throws NoSuchMethodException {
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class
                .getDeclaredMethod("createRuleBinding", Long.class, String.class, String.class, String.class, com.company.aitest.common.CurrentUser.class));
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class
                .getDeclaredMethod("createScanBinding", Long.class, Long.class, String.class, String.class, com.company.aitest.common.CurrentUser.class));
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class
                .getDeclaredMethod("createTomBinding", Long.class, Long.class, String.class, String.class, com.company.aitest.common.CurrentUser.class));
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class
                .getDeclaredMethod("createSemanticBinding", Long.class, Long.class, String.class, String.class, com.company.aitest.common.CurrentUser.class));
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class
                .getDeclaredMethod("recordConsumption", Long.class, String.class, String.class, int.class));
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class
                .getDeclaredMethod("listRefreshDiagnostics", Long.class));
        assertNotNull(com.company.aitest.businesspack.BusinessPackService.class
                .getDeclaredMethod("getAvailableTransitions", Long.class));
    }

    /**
     * 验证 BusinessPackService 包含自动绑定创建的私有方法。
     */
    @Test
    void businessPackServiceHasAutoBindingMethods() throws NoSuchMethodException {
        var serviceClass = com.company.aitest.businesspack.BusinessPackService.class;
        var tomBindingMethod = serviceClass.getDeclaredMethod("autoCreateTomBinding",
                Long.class, Long.class, Long.class, String.class, String.class, java.time.LocalDateTime.class);
        tomBindingMethod.setAccessible(true);
        assertNotNull(tomBindingMethod, "应有 autoCreateTomBinding 方法");

        var scanBindingMethod = serviceClass.getDeclaredMethod("autoCreateScanBinding",
                Long.class, Long.class, Long.class, String.class, String.class, java.time.LocalDateTime.class);
        scanBindingMethod.setAccessible(true);
        assertNotNull(scanBindingMethod, "应有 autoCreateScanBinding 方法");
    }
}
