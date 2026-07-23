package com.company.aitest.export;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.project.ProjectAccessService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/export")
public class CaseExportController {

    private final CaseExportService caseExportService;
    private final ProjectAccessService projectAccessService;

    public CaseExportController(CaseExportService caseExportService, ProjectAccessService projectAccessService) {
        this.caseExportService = caseExportService;
        this.projectAccessService = projectAccessService;
    }

    @PostMapping("/formal-cases")
    public ResponseEntity<byte[]> exportFormalCases(
            @PathVariable Long projectId,
            @RequestBody(required = false) List<Long> caseIds,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        byte[] xmind = caseExportService.exportFormalCasesToXmind(projectId, caseIds);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"formal-cases.xmind\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(xmind);
    }

    @PostMapping("/local-cases")
    public ResponseEntity<byte[]> exportLocalCases(
            @PathVariable Long projectId,
            @RequestBody(required = false) List<Long> caseIds,
            Authentication authentication) {
        CurrentUser user = (CurrentUser) authentication.getPrincipal();
        projectAccessService.ensureCanAccess(projectId, user);
        byte[] xmind = caseExportService.exportLocalCasesToXmind(projectId, caseIds, user);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"local-cases.xmind\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(xmind);
    }
}
