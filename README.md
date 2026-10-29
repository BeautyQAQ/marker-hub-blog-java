# vue-blog
参考https://github.com/MarkerHub/vueblog

数据库使用 DBX 的 `nas-mysql` 测试实例，对应本机可访问的 `192.168.20.5:3306/marker-hub`。

数据源地址、用户名和密码统一保存在 `src/main/resources/application.yml`；应用和 `CodeGenerator` 共用这份配置。需要覆盖连接时，可使用标准 Spring 环境变量 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。

JDBC URL 同时指定 `serverTimezone=UTC` 和 `sessionVariables`，每条连接的会话时区为 `+00:00`，与表中 DATETIME 的 UTC 约定一致。URL 中的时区符号已经编码。

建库建表脚本及核验记录见 [sql/README.md](sql/README.md)。`CodeGenerator` 会生成源码，不要把运行代码生成器作为数据库连通性检查。
