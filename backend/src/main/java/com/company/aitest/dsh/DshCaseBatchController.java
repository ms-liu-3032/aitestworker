package com.company.aitest.dsh;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dsh/tools/projects/{projectId}/case-batch")
public class DshCaseBatchController {
    private final DshCaseBatchService batches;

    public DshCaseBatchController(DshCaseBatchService batches) { this.batches = batches; }

    @PostMapping
    public ApiResponse<List<DshCaseBatchService.ItemResult>> create(@PathVariable Long projectId,
            @RequestBody DshCaseBatchService.BatchInput input, Authentication authentication) {
        CurrentUser user = ((DshConnectionService.DshPrincipal) authentication.getPrincipal()).user();
        return ApiResponse.ok(batches.create(projectId, input, user));
    }
}
