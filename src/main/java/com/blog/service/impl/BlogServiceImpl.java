package com.blog.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.blog.common.ApiException;
import com.blog.dto.BlogEditRequest;
import com.blog.dto.BlogPage;
import com.blog.dto.BlogSummary;
import com.blog.entity.Blog;
import com.blog.mapper.BlogMapper;
import com.blog.service.BlogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements BlogService {
    public static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private static final int DRAFT = 0;
    private static final int PUBLISHED = 1;
    private static final int DELETED = -1;

    @Override
    public BlogPage listPublished(long currentPage, long pageSize) {
        return listPage(currentPage, pageSize, summaryQuery().eq(Blog::getStatus, PUBLISHED));
    }

    @Override
    public BlogPage listForAuthor(long authorId, long currentPage, long pageSize, Integer status) {
        requireAuthor(authorId);
        validateStatus(status);
        LambdaQueryWrapper<Blog> query = summaryQuery().eq(Blog::getUserId, authorId)
                .in(Blog::getStatus, DRAFT, PUBLISHED);
        if (status != null) {
            query.eq(Blog::getStatus, status);
        }
        return listPage(currentPage, pageSize, query);
    }

    @Override
    public Blog findVisible(long id, Long viewerId) {
        Blog blog = existing(id);
        if (blog.getStatus() == PUBLISHED
                || blog.getStatus() == DRAFT && Objects.equals(blog.getUserId(), viewerId)) {
            return blog;
        }
        throw notFound();
    }

    @Override
    @Transactional
    public Blog saveForAuthor(long authorId, BlogEditRequest request) {
        requireAuthor(authorId);
        validateRequest(request);
        String title = request.title().strip();
        String description = request.description() == null ? "" : request.description();
        if (request.id() == null) {
            Blog blog = new Blog().setUserId(authorId).setTitle(title).setDescription(description)
                    .setContent(request.content()).setStatus(request.status() == null ? DRAFT : request.status())
                    .setCreated(new Date());
            if (baseMapper.insert(blog) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "文章保存失败，请重试");
            }
            // Return stored DATETIME precision rather than the in-memory millisecond timestamp.
            return existing(blog.getId());
        }

        Blog previous = existing(request.id());
        requireOwner(previous, authorId);
        LambdaUpdateWrapper<Blog> update = new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, previous.getId()).eq(Blog::getUserId, authorId)
                .in(Blog::getStatus, DRAFT, PUBLISHED)
                .set(Blog::getTitle, title).set(Blog::getDescription, description)
                .set(Blog::getContent, request.content());
        if (request.status() != null) {
            update.set(Blog::getStatus, request.status());
        }
        if (baseMapper.update(null, update) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "文章已发生变化，请刷新后重试");
        }
        return existing(previous.getId());
    }

    @Override
    @Transactional
    public void deleteForAuthor(long authorId, long id) {
        requireAuthor(authorId);
        Blog blog = existing(id);
        requireOwner(blog, authorId);
        int affected = baseMapper.update(null, new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, id).eq(Blog::getUserId, authorId)
                .in(Blog::getStatus, DRAFT, PUBLISHED).set(Blog::getStatus, DELETED));
        if (affected != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "文章已发生变化，请刷新后重试");
        }
    }

    private LambdaQueryWrapper<Blog> summaryQuery() {
        return new LambdaQueryWrapper<Blog>().select(Blog::getId, Blog::getUserId, Blog::getTitle,
                Blog::getDescription, Blog::getCreated, Blog::getStatus)
                .orderByDesc(Blog::getCreated, Blog::getId);
    }

    private BlogPage listPage(long currentPage, long pageSize, LambdaQueryWrapper<Blog> query) {
        if (currentPage < 1 || currentPage > Integer.MAX_VALUE || pageSize < 1 || pageSize > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "分页参数无效，页码须为正数，每页须为1到20条");
        }
        Page<Blog> result = baseMapper.selectPage(new Page<>(currentPage, pageSize), query);
        return new BlogPage(result.getRecords().stream().map(BlogSummary::from).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    private Blog existing(long id) {
        if (id <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文章ID无效");
        }
        Blog blog = baseMapper.selectById(id);
        if (blog == null || !Objects.equals(blog.getStatus(), DRAFT) && !Objects.equals(blog.getStatus(), PUBLISHED)) {
            throw notFound();
        }
        return blog;
    }

    private void requireOwner(Blog blog, long authorId) {
        if (!Objects.equals(blog.getUserId(), authorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能修改自己的文章");
        }
    }

    private void requireAuthor(long authorId) {
        if (authorId <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
    }

    private void validateRequest(BlogEditRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请提交文章内容");
        }
        if (request.id() != null && request.id() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文章ID无效");
        }
        if (request.title() == null || request.title().isBlank()
                || codePoints(request.title().strip()) > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "标题不能为空且最多255个字符");
        }
        if (request.description() != null && codePoints(request.description()) > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "摘要最多1000个字符");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "正文不能为空");
        }
        if (request.content().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "正文不能超过1MiB");
        }
        validateStatus(request.status());
    }

    private int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private void validateStatus(Integer status) {
        if (status != null && status != DRAFT && status != PUBLISHED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文章状态只能是草稿或已发布");
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "文章不存在");
    }

}
