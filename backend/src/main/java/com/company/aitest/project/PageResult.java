package com.company.aitest.project;

import java.util.List;

public record PageResult<T>(List<T> items, int total, int page, int pageSize) {
    public int totalPages() {
        return pageSize > 0 ? (total + pageSize - 1) / pageSize : 0;
    }
}
