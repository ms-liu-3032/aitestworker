package com.company.aitest.generation.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementAnalysisSchemaContractTest {

    private static final int STATUS_COLUMN_LENGTH = 128;
    private static final Pattern STATUS_LITERAL = Pattern.compile("status\\s*=\\s*'([A-Z_]+)'");

    @Test
    void migrationSupportsAllRequirementAnalysisWorkflowStatuses() throws IOException {
        var migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V51__harden_generation_workflow_column_capacity.sql"));
        assertTrue(migration.matches("(?s).*requirement_analysis.*status\\s+VARCHAR\\(128\\).*"));
        for (String table : new String[]{
                "generation_session", "generation_message", "generation_task", "generation_question",
                "prompt_snapshot", "context_manifest", "llm_invocation_log", "security_event_log",
                "generation_task_stage_checkpoint", "skill_execution_log", "generation_attachment"
        }) {
            assertTrue(migration.contains("ALTER TABLE " + table),
                    () -> "V51 must harden generation workflow table: " + table);
        }

        var serviceSource = Files.readString(Path.of(
                "src/main/java/com/company/aitest/generation/session/RequirementAnalysisService.java"));
        Set<String> declaredStatuses = Arrays.stream(RequirementAnalysisStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        var matcher = STATUS_LITERAL.matcher(serviceSource);
        var foundStatus = false;
        while (matcher.find()) {
            foundStatus = true;
            var status = matcher.group(1);
            assertTrue(declaredStatuses.contains(status),
                    () -> "Undeclared requirement-analysis workflow state: " + status);
            assertTrue(status.length() <= STATUS_COLUMN_LENGTH,
                    () -> "requirement_analysis.status cannot store workflow state: " + status);
        }
        assertTrue(foundStatus, "Expected requirement-analysis workflow status literals");
        assertFalse("NEED_TEST_POINT_SCOPE_CONFIRMATION".length() <= 32,
                "Regression fixture must remain longer than the legacy VARCHAR(32)");
    }

    @Test
    void allDeclaredAnalysisStatusesFitTheDatabaseContract() {
        for (RequirementAnalysisStatus status : RequirementAnalysisStatus.values()) {
            assertTrue(status.name().length() <= STATUS_COLUMN_LENGTH,
                    () -> "requirement_analysis.status cannot store workflow state: " + status);
        }
    }

    @Test
    void sessionControlValuesAreNormalizedAndUnknownValuesAreRejectedBeforePersistence() {
        assertEquals("WAITING_USER_CONFIRMATION",
                GenerationSessionService.requireSessionStage(" waiting_user_confirmation "));
        assertEquals("GENERATING", GenerationSessionService.requireSessionStatus("generating"));
        assertThrows(RuntimeException.class,
                () -> GenerationSessionService.requireSessionStage("BUSINESS_SPECIFIC_DYNAMIC_STAGE"));
        assertThrows(RuntimeException.class,
                () -> GenerationSessionService.requireSessionStatus("MODEL_GENERATED_STATUS"));
    }

    @Test
    void functionalCaseScenarioMigrationCoversDraftAndFormalLibraries() throws IOException {
        var migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V52__functional_case_scenario_governance.sql"));

        assertTrue(migration.matches("(?s).*ALTER TABLE test_case_draft.*scenario_type VARCHAR\\(32\\).*"));
        assertTrue(migration.matches("(?s).*ALTER TABLE test_case_asset.*scenario_type VARCHAR\\(32\\).*"));
        assertTrue(migration.contains("DEFAULT 'POSITIVE'"));
    }
}
