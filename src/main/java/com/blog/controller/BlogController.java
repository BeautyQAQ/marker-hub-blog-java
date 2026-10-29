package com.blog.controller;

import com.blog.common.ApiException;
import com.blog.common.Result;
import com.blog.dto.BlogEditRequest;
import com.blog.service.BlogService;
import com.blog.shiro.AccountProfile;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.subject.Subject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BlogController {
    private final BlogService blogService;

    public BlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    @GetMapping("/blogs")
    public Result published(@RequestParam(defaultValue = "1") long currentPage,
                            @RequestParam(defaultValue = "5") long pageSize) {
        return Result.successful("查询成功", blogService.listPublished(currentPage, pageSize));
    }

    @RequiresAuthentication
    @GetMapping("/my/blogs")
    public Result own(@RequestParam(defaultValue = "1") long currentPage,
                      @RequestParam(defaultValue = "5") long pageSize,
                      @RequestParam(required = false) Integer status) {
        return Result.successful("查询成功", blogService.listForAuthor(authorId(), currentPage, pageSize, status));
    }

    @GetMapping("/blog/{id}")
    public Result detail(@PathVariable long id) {
        return Result.successful("查询成功", blogService.findVisible(id, viewerId()));
    }

    @RequiresAuthentication
    @PostMapping("/blog/edit")
    public Result edit(@RequestBody BlogEditRequest request) {
        return Result.successful("保存成功", blogService.saveForAuthor(authorId(), request));
    }

    @RequiresAuthentication
    @DeleteMapping("/blog/{id}")
    public Result delete(@PathVariable long id) {
        blogService.deleteForAuthor(authorId(), id);
        return Result.successful("删除成功", null);
    }

    private Long viewerId() {
        Subject subject = SecurityUtils.getSubject();
        return subject.isAuthenticated() && subject.getPrincipal() instanceof AccountProfile profile
                ? profile.getId() : null;
    }

    private long authorId() {
        Long id = viewerId();
        if (id == null || id <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return id;
    }
}
