package com.blog.dto;

import java.util.List;

public record BlogPage(List<BlogSummary> records, long current, long size, long total) {
    public BlogPage {
        records = List.copyOf(records);
    }
}
