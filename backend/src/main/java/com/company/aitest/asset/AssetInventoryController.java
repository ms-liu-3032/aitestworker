package com.company.aitest.asset;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/assets/inventory")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class AssetInventoryController {
    private final AssetInventoryService inventory;

    public AssetInventoryController(AssetInventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public ApiResponse<List<AssetInventoryService.InventoryRow>> list(
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(inventory.list(projectId));
    }
}
