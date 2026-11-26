package com.company.aitest.asset;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.project.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/assets/catalog")
@PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
public class AssetCatalogController {
    private final AssetCatalogService catalog;

    public AssetCatalogController(AssetCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ApiResponse<PageResult<AssetCatalogService.AssetRow>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(catalog.list(projectId, type, status, keyword, page, size));
    }
}
