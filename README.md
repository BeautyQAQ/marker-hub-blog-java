# marker-hub-blog-java
参考https://github.com/MarkerHub/vueblog

数据库使用 DBX 的 `nas-mysql` 测试实例，对应本机可访问的 `192.168.20.5:3306/marker-hub`。

数据源地址、用户名和密码统一保存在 `src/main/resources/application.yml`；应用和 `CodeGenerator` 共用这份配置。需要覆盖连接时，可使用标准 Spring 环境变量 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。

JDBC URL 同时指定 `serverTimezone=UTC` 和 `sessionVariables`，每条连接的会话时区为 `+00:00`，与表中 DATETIME 的 UTC 约定一致。URL 中的时区符号已经编码。

建库建表脚本及核验记录见 [sql/README.md](sql/README.md)。`CodeGenerator` 会生成源码，不要把运行代码生成器作为数据库连通性检查。

## 开发与运行

使用 JDK 21 和 Maven 3.6.3 或以上版本。先用 `java -version`、`mvn -version` 确认两个命令都使用 JDK 21，然后执行：

```sh
mvn clean verify
java -jar target/vue-blog-1.0-SNAPSHOT.jar
```

后端默认监听 8080。数据库和 Redis 须可连接；普通启动不会创建账号或修改已有账号。

当前依赖为 Spring Boot 4.1.2、Shiro 3.0.1、MyBatis-Plus 3.5.18、Druid 1.2.28 和 JJWT 0.13.0。Servlet API 已迁移为 Jakarta。分页使用 `MybatisPlusInterceptor` 与 `PaginationInnerInterceptor`；代码生成器使用 `FastAutoGenerator`，保留 `Date` 和 `Integer` 状态字段映射。

Redis 使用 DBX 的 `nas-redis` 实例，对应 `192.168.20.5:6379`、逻辑库 0。连接和认证信息保存在 `application.yml` 的 `shiro-redis.redis-manager` 下，可通过对应 Spring 环境变量覆盖。登录令牌、登录限流和 Shiro 适配器均使用 `marker-hub:` 键前缀，可与其他应用共享 Redis；无需清空数据库。

## 登录与站长初始化

项目不提供公开注册。第一次使用时，显式执行一次初始化命令：

```sh
java -jar target/vue-blog-1.0-SNAPSHOT.jar --spring.profiles.active=bootstrap-admin
```

默认用户名为 `admin`，可用 `BLOG_ADMIN_USERNAME` 覆盖。程序从终端交互读取密码；无交互终端时从标准输入读取一行，也支持 `BLOG_ADMIN_PASSWORD` 环境变量。密码至少 8 个字符且 UTF-8 编码不超过 72 字节，避免通过命令行参数传入密码。初始化使用 BCrypt 保存密码，成功后自动退出；已有同名账号时拒绝执行，绝不覆盖密码。该 profile 不启动 HTTP 服务或 Redis 认证组件，只需要数据库可用。创建账号后，使用普通启动命令运行服务。

| 接口 | 行为 |
| --- | --- |
| `POST /login` | JSON 请求 `{"username":"admin","password":"输入你的密码"}`；返回安全用户资料，并在响应 `Authorization` 头中返回 JWT。 |
| `GET /me` | 返回当前登录用户的 ID、用户名、头像和邮箱。 |
| `GET /user/{id}` | 仅允许读取本人资料，不返回密码或密码哈希。 |
| `POST /logout`、`GET /logout` | 撤销当前 JWT，随后再使用该令牌会返回 401；其他登录令牌保持有效。 |

登录后的请求通过 `Authorization: Bearer <token>` 或原始 token 传递令牌。JWT 使用 HS512，每个令牌具有独立 ID；Redis 只保存令牌的 SHA-256 摘要和用户 ID，TTL 与 JWT 剩余有效期对应。每次请求检查签名、有效期、Redis 许可记录和数据库用户状态，账号锁定立即生效。Shiro 不自动保存登录会话，也不缓存认证结果。Redis 故障时认证接口返回 503。

登录按客户端 IP 每分钟 30 次、按用户名每分钟 10 次计数，超限返回 429。`POST /login` 会忽略浏览器旧令牌，以便重新登录。错误响应保留 `{code,msg,data}` 结构，同时使用实际的 400、401、403、404、405、413、415 等 HTTP 状态。

CORS 默认允许 `http://localhost:5173` 和 `http://127.0.0.1:5173`，并向浏览器暴露 `Authorization` 响应头。部署时通过 `BLOG_ALLOWED_ORIGINS` 设置逗号分隔的前端来源。JSON 请求默认最多 2 MiB，可通过 `blog.http.max-request-bytes` 配置。

## 文章接口

| 接口 | 行为 |
| --- | --- |
| `GET /blogs?currentPage=1&pageSize=5` | 公开分页列表，只返回已发布文章的摘要，每页 1 至 20 条。 |
| `GET /blog/{id}` | 公开读取已发布文章；草稿仅作者登录后可读。不存在或已删除的文章返回 404。 |
| `GET /my/blogs?currentPage=1&pageSize=5&status=0` | 登录后读取自己的文章；`status` 可省略，0 为草稿，1 为已发布。 |
| `POST /blog/edit` | 登录后新建或修改自己的文章，JSON 字段为 `id`（新建时省略）、`title`、`description`、`content`、`status`。新建默认草稿。 |
| `DELETE /blog/{id}` | 作者登录后软删除，数据库 `status` 置为 -1。 |

作者和创建时间由服务端确定。客户端无法修改他人的文章，修改文章会保留原作者和创建时间。标题最多 255 个字符，摘要最多 1000 个字符，正文按 UTF-8 编码最多 1 MiB；正文按纯文本或 Markdown 原文保存。

## 验证

`mvn verify` 当前执行 36 个测试，覆盖 JWT、Redis 协议兼容及许可记录、真实 HTTP 登录/退出/锁定/越权/CORS/请求大小、Shiro 注解拦截、显式非 Web 账号初始化，以及 H2 上的文章 SQL 和软删除。测试使用隔离数据源和本地测试替身，不连接配置中的 MySQL 或 Redis。GitHub Actions 在 main 推送和 PR 中使用 Temurin 21 执行这些检查。

可选的实际服务验收脚本使用 Node.js 24：设置 `BLOG_API_URL`（默认 `http://127.0.0.1:8080`）、`BLOG_SMOKE_USERNAME`（默认 `admin`）和 `BLOG_SMOKE_PASSWORD` 环境变量后运行：

```sh
node scripts/smoke-api.mjs
```

该脚本会登录实际服务并创建带 `smoke-` 时间戳前缀的测试文章，验证草稿、发布、编辑、分页、删除和退出流程。结束时尝试软删除测试文章并退出；数据库中仍会留下 `status=-1` 的测试记录，连接中断时可能需要按脚本输出的文章 ID 手动清理。

升级依据：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Shiro Spring Boot 集成](https://shiro.apache.org/spring-boot.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/install/)、[分页插件](https://baomidou.com/en/plugins/pagination/)、[新版生成器](https://baomidou.com/en/guides/new-code-generator/)。
