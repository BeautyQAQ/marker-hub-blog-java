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

后端默认监听 8080。业务接口仍是现有骨架：`GET /user/{id}`；登录、文章列表、详情及编辑接口尚未实现，本次技术栈升级没有增加这些接口。

当前依赖为 Spring Boot 4.1.2、Shiro 3.0.1、MyBatis-Plus 3.5.18、Druid 1.2.28 和 JJWT 0.13.0。Servlet API 已迁移为 Jakarta。分页使用 `MybatisPlusInterceptor` 与 `PaginationInnerInterceptor`；代码生成器使用 `FastAutoGenerator`，保留 `Date` 和 `Integer` 状态字段映射。

Redis 会话和缓存继续使用 Shiro 的 `RedisSessionDAO`、`RedisCacheManager`。旧 Boot 2 Redis starter 改为显式配置，`JedisRedisManager` 通过当前 Jedis API 适配原接口，并用 SCAN 遍历缓存键。连接仍由 `shiro-redis.redis-manager.host` 配置；可选 `password`、`database`、`timeout`，默认值分别为空、0、2000 毫秒。需要使用认证或会话功能时，目标 Redis 必须可访问。

JWT 继续使用 HS512。升级同时更新为随机生成的 512 位 Base64 签名密钥，以满足新版 JJWT 的密钥强度校验；升级前签发的 token 需重新获取。数据库凭据保持现有配置。

`mvn verify` 包含 JWT 校验测试、通过本地 RESP 测试服务执行的 Redis 缓存/会话/二进制协议兼容测试，以及替换数据源和用户服务后的完整 Boot 启动及 HTTP 回归测试；测试不会连接项目配置的数据库或 Redis。GitHub Actions 在 main 推送和 PR 中使用 Temurin 21 执行这些检查。

升级依据：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Shiro Spring Boot 集成](https://shiro.apache.org/spring-boot.html)、[MyBatis-Plus 安装](https://baomidou.com/en/getting-started/install/)、[分页插件](https://baomidou.com/en/plugins/pagination/)、[新版生成器](https://baomidou.com/en/guides/new-code-generator/)。
