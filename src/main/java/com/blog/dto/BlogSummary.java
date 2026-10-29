package com.blog.dto;

import com.blog.entity.Blog;

import java.util.Date;

/** List responses deliberately omit the Markdown body. */
public record BlogSummary(Long id, Long userId, String title, String description, Date created, Integer status) {
    public static BlogSummary from(Blog blog) {
        return new BlogSummary(blog.getId(), blog.getUserId(), blog.getTitle(), blog.getDescription(),
                blog.getCreated(), blog.getStatus());
    }
}
