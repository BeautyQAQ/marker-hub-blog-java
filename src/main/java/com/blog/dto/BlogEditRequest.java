package com.blog.dto;

/** Only editable fields are accepted; author and creation time come from the server. */
public record BlogEditRequest(Long id, String title, String description, String content, Integer status) {
}
