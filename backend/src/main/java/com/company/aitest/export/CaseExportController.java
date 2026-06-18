package com.company.aitest.export;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/export")
public class CaseExportController {

    private final CaseExportService caseExportService;

    public CaseExportController(CaseExportService caseExportService) {
        this.caseExportService = caseExportService;
    }

    @PostMapping("/formal-cases")
    public ResponseEntity<byte[]> exportFormalCases(
            @PathVariable Long projectId,
            @RequestBody(required = false) List<Long> caseIds) {
        byte[] xmind = caseExportService.exportFormalCasesToXmind(projectId, caseIds);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"formal-cases.xmind\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(xmind);
    }

    @PostMapping("/local-cases")
    public ResponseEntity<byte[]> exportLocalCases(
            @PathVariable Long projectId,
            @RequestBody(required = false) List<Long> caseIds) {
        byte[] xmind = caseExportService.exportLocalCasesToXmind(projectId, caseIds);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"local-cases.xmind\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(xmind);
    }
}
