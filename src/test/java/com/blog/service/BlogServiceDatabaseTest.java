package com.blog.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.blog.common.ApiException;
import com.blog.config.MybatisPlusConfig;
import com.blog.dto.BlogEditRequest;
import com.blog.dto.BlogPage;
import com.blog.dto.BlogSummary;
import com.blog.entity.Blog;
import com.blog.service.impl.BlogServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real MyBatis queries against an isolated SQL database; no NAS or Redis connections. */
@SpringJUnitConfig(BlogServiceDatabaseTest.DatabaseConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class BlogServiceDatabaseTest {
    @Autowired
    private BlogService blogs;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void createSchema() {
        jdbc.execute("""
                CREATE TABLE m_blog (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    description VARCHAR(1000) NOT NULL DEFAULT '',
                    content CLOB NOT NULL,
                    created TIMESTAMP(0) NOT NULL,
                    status TINYINT NOT NULL DEFAULT 0
                )
                """);
    }

    @Test
    void publicPagesContainOnlyPublishedRowsAndUseStableDateThenIdOrdering() {
        row(11, 7, 1, "2026-10-01T12:00:00Z");
        row(12, 7, 1, "2026-10-02T12:00:00Z");
        row(13, 9, 1, "2026-10-02T12:00:00Z");
        row(14, 7, 0, "2026-10-03T12:00:00Z");
        row(15, 7, -1, "2026-10-04T12:00:00Z");

        BlogPage first = blogs.listPublished(1, 2);
        assertEquals(List.of(13L, 12L), first.records().stream().map(BlogSummary::id).toList());
        assertEquals(3, first.total());
        assertEquals(1, first.current());
        assertEquals(2, first.size());
        BlogPage second = blogs.listPublished(2, 2);
        assertEquals(List.of(11L), second.records().stream().map(BlogSummary::id).toList());
        assertEquals(3, second.total());
        assertTrue(blogs.listPublished(3, 2).records().isEmpty());
    }

    @Test
    void ownPagesFilterByAuthenticatedAuthorAndOptionalStatus() {
        row(11, 7, 0);
        row(12, 7, 1);
        row(13, 7, -1);
        row(14, 9, 0);
        row(15, 9, 1);

        assertEquals(List.of(12L, 11L), blogs.listForAuthor(7, 1, 5, null)
                .records().stream().map(BlogSummary::id).toList());
        assertEquals(List.of(11L), blogs.listForAuthor(7, 1, 5, 0)
                .records().stream().map(BlogSummary::id).toList());
        assertEquals(List.of(12L), blogs.listForAuthor(7, 1, 5, 1)
                .records().stream().map(BlogSummary::id).toList());
        assertEquals(0, blogs.listForAuthor(99, 1, 5, null).total());
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.listForAuthor(7, 1, 5, -1));
    }

    @Test
    void draftDetailsArePrivateAndDeletedArticlesAreInvisibleToEveryone() {
        row(11, 7, 0);
        row(12, 7, 1);
        row(13, 7, -1);

        assertEquals(11L, blogs.findVisible(11, 7L).getId());
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(11, null));
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(11, 9L));
        assertEquals(12L, blogs.findVisible(12, null).getId());
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(13, 7L));
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(13, null));
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(999, null));
    }

    @Test
    void createDefaultsToDraftAndSetsAuthorAndCreationTimeOnTheServer() {
        long before = System.currentTimeMillis();
        Blog created = blogs.saveForAuthor(7, new BlogEditRequest(null, "  新文章  ", null, "# 内容\n\n正文", null));
        assertNotNull(created.getId());
        Blog stored = blogs.findVisible(created.getId(), 7L);
        assertEquals(stored.getCreated(), created.getCreated(), "Create must return the stored timestamp precision");
        assertEquals(0, created.getCreated().getTime() % 1000);
        assertEquals(7L, stored.getUserId());
        assertEquals(0, stored.getStatus());
        assertEquals("新文章", stored.getTitle());
        assertEquals("", stored.getDescription());
        assertEquals("# 内容\n\n正文", stored.getContent());
        assertTrue(stored.getCreated().getTime() >= before - 1000);
        assertTrue(stored.getCreated().getTime() <= System.currentTimeMillis() + 1000);
        assertEquals(0, blogs.listPublished(1, 5).total());
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(created.getId(), null));
    }

    @Test
    void editingCanPublishAndUnpublishButPreservesAuthorAndOriginalCreationTime() {
        row(11, 7, 0);
        Date createdAt = blogs.findVisible(11, 7L).getCreated();
        Blog published = blogs.saveForAuthor(7, new BlogEditRequest(11L, "发布", "摘要", "  缩进代码\n", 1));
        assertEquals(1, published.getStatus());
        assertEquals(11L, blogs.findVisible(11, null).getId());
        assertEquals(createdAt, published.getCreated());
        assertEquals(7L, published.getUserId());
        assertEquals("  缩进代码\n", published.getContent());

        Blog edited = blogs.saveForAuthor(7, new BlogEditRequest(11L, "只编辑内容", "", "修改后的内容", null));
        assertEquals(1, edited.getStatus(), "Omitting status must preserve the stored status");
        assertEquals(createdAt, edited.getCreated());
        blogs.saveForAuthor(7, new BlogEditRequest(11L, "转为草稿", "", "草稿正文", 0));
        assertEquals(0, blogs.listPublished(1, 5).total());
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(11, null));
    }

    @Test
    void anotherAuthorCannotEditOrDeleteAndDeletedArticlesCannotBeRestoredByEdit() {
        row(11, 7, 1);
        assertStatus(HttpStatus.FORBIDDEN,
                () -> blogs.saveForAuthor(9, new BlogEditRequest(11L, "篡改", "", "攻击内容", 1)));
        assertStatus(HttpStatus.FORBIDDEN, () -> blogs.deleteForAuthor(9, 11));
        assertEquals("article-11", blogs.findVisible(11, null).getTitle());

        blogs.deleteForAuthor(7, 11);
        assertEquals(-1, jdbc.queryForObject("SELECT status FROM m_blog WHERE id=11", Integer.class));
        assertEquals(0, blogs.listPublished(1, 5).total());
        assertEquals(0, blogs.listForAuthor(7, 1, 5, null).total());
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.findVisible(11, 7L));
        assertStatus(HttpStatus.NOT_FOUND, () -> blogs.deleteForAuthor(7, 11));
        assertStatus(HttpStatus.NOT_FOUND,
                () -> blogs.saveForAuthor(7, new BlogEditRequest(11L, "恢复", "", "正文", 1)));
    }

    @Test
    void validationRejectsInvalidPaginationIdsAndEditableFieldsWithoutWrites() {
        for (long page : List.of(0L, -1L, Long.MAX_VALUE)) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.listPublished(page, 5));
        }
        for (long size : List.of(0L, -1L, 21L)) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.listPublished(1, size));
        }
        assertStatus(HttpStatus.UNAUTHORIZED, () -> blogs.listForAuthor(0, 1, 5, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.findVisible(0, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.saveForAuthor(7, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.saveForAuthor(7, request(0L, "标题", "", "正文", 1)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.saveForAuthor(7, request(null, " \n ", "", "正文", 1)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.saveForAuthor(7, request(null, "a".repeat(256), "", "正文", 1)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.saveForAuthor(7, request(null, "标题", "a".repeat(1001), "正文", 1)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.saveForAuthor(7, request(null, "标题", "", "\n\t", 1)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> blogs.saveForAuthor(7, request(null, "标题", "", "正文", -1)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM m_blog", Integer.class));
    }

    @Test
    void bodyLimitUsesUtf8BytesAndAcceptsTheExactBoundary() {
        String exact = "中".repeat(BlogServiceImpl.MAX_CONTENT_BYTES / 3) + "a";
        Blog blog = blogs.saveForAuthor(7, request(null, "a".repeat(255), "a".repeat(1000), exact, 1));
        assertEquals(exact, blogs.findVisible(blog.getId(), null).getContent());
        assertStatus(HttpStatus.PAYLOAD_TOO_LARGE,
                () -> blogs.saveForAuthor(7, request(null, "正文太长", "", exact + "b", 1)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM m_blog", Integer.class));
    }

    private BlogEditRequest request(Long id, String title, String description, String content, Integer status) {
        return new BlogEditRequest(id, title, description, content, status);
    }

    private void row(long id, long owner, int status) {
        row(id, owner, status, "2026-10-01T12:00:00Z");
    }

    private void row(long id, long owner, int status, String created) {
        jdbc.update("INSERT INTO m_blog (id,user_id,title,description,content,created,status) VALUES(?,?,?,?,?,?,?)",
                id, owner, "article-" + id, "summary-" + id, "private-body-" + id,
                Timestamp.from(Instant.parse(created)), status);
    }

    private void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ApiException.class, action).getStatus());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(MybatisPlusConfig.class)
    static class DatabaseConfig {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:blog_service_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setDataSource(dataSource);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        BlogService blogService() {
            return new BlogServiceImpl();
        }
    }
}
