package com.smartlib.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Stable pagination envelope used by every list endpoint (page is 0-based). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    public static <T, R> PageResponse<R> of(Page<T> page, Function<T, R> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static int clampSize(Integer size, int defaultSize, int max) {
        if (size == null || size <= 0) {
            return defaultSize;
        }
        return Math.min(size, max);
    }

    public static int clampPage(Integer page) {
        return page == null || page < 0 ? 0 : Math.min(page, 100_000);
    }
}
