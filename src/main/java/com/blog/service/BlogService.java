package com.blog.service;

import com.blog.entity.Blog;
import com.blog.dto.BlogEditRequest;
import com.blog.dto.BlogPage;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author huangshen
 * @since 2020-06-04
 */
public interface BlogService extends IService<Blog> {
    BlogPage listPublished(long currentPage, long pageSize);

    BlogPage listForAuthor(long authorId, long currentPage, long pageSize, Integer status);

    Blog findVisible(long id, Long viewerId);

    Blog saveForAuthor(long authorId, BlogEditRequest request);

    void deleteForAuthor(long authorId, long id);
}
