# 数据库初始化

数据库名称固定为 `marker-hub`，目标是 dbx 中的 `nas-mysql` 测试连接。

在该连接的 SQL 编辑器中打开并整体执行 [001_init_marker_hub.sql](001_init_marker_hub.sql)。账号需要对目标数据库拥有建库、建表及创建外键所需权限。脚本仅创建数据库和两张表，不创建数据库账号、不导入数据，也不配置应用连接。

2026-10-29 用户手工执行脚本后，已通过更新后的 DBX HTTP MCP 对 `nas-mysql` 完成只读验收：

- 版本：MySQL Community Server 8.1.0。
- `marker-hub` 数据库及 `m_user` / `m_blog` 表均已创建，目前两张表各有 0 条记录。
- SHOW CREATE 确认两表字段、默认值、用户名唯一索引、文章列表索引和作者外键均与初始化脚本一致。
- InnoDB 为默认存储引擎，`utf8mb4_unicode_ci` 排序规则存在。
- 当前连接具有全局 CREATE、REFERENCES、INDEX 等相关权限，并具有 SHOW DATABASES 权限。
- 会话时区为 SYSTEM，当前 UTC 偏移为 +08:00；服务器默认排序规则为 `utf8mb4_0900_ai_ci`，本脚本显式选用 `utf8mb4_unicode_ci`。

现有 MCP 配置已改为 HTTP `/mcp` 入口并配置 Authorization。已成功初始化 MCP 会话并调用 dbx 工具进行验收。本次未修改 MCP 配置、数据库结构、数据或访问令牌。

后端数据源已改为本机可访问的 `192.168.20.5:3306/marker-hub`，凭据按用户要求保存在 application.yml；代码生成器读取同一份配置。使用与项目匹配的 Connector/J 8.0.19、Spring 5.2.5 和 SnakeYAML 1.25 加载实际配置完成只读 JDBC 验证：DATABASE() 返回 marker-hub，会话 time_zone 为 +00:00，用户 8 个字段和文章 7 个字段查询均通过。未运行代码生成器，也未启动完整后端服务。

| 表 | 对应实体 | 约束与索引 |
| --- | --- | --- |
| `m_user` | `com.blog.entity.User` | BIGINT 自增主键；username 唯一；status 非空 |
| `m_blog` | `com.blog.entity.Blog` | BIGINT 自增主键；作者外键；公开列表和作者列表索引 |

字段名保持现有映射：`userId -> user_id`、`lastLogin -> last_login`。id 与 user_id 使用有符号 BIGINT，status 使用有符号 TINYINT，兼容 Java Long 和锁定值 -1。用户名唯一约束在所选排序规则下不区分大小写。

用户状态沿用当前代码的锁定约定：0 正常、-1 锁定。文章状态是本次为草稿/发布/软删除新定义的约定：0 草稿、1 已发布、-1 已删除。后续后端需要实现状态校验，公开列表/详情只允许 status=1，发布时显式设置 1，管理端查询自己的草稿。数据库不会自动实现这些业务规则。

创建文章前需要先有合法用户，外键会拒绝不存在的作者；删除仍有关联文章的用户也会被拒绝。脚本不预置用户，避免写入默认密码。password 列预留 255 字符，供后续认证实现写入密码摘要。

时间字段使用 DATETIME，约定保存 UTC。DBX 的默认会话为 UTC+8；应用的 JDBC URL 已同时设置 serverTimezone=UTC 和编码后的 sessionVariables，使每条 JDBC 连接的会话时区为 +00:00。初始化脚本的 SET SESSION 仅影响执行脚本的会话，未修改服务器全局时区。

`CREATE ... IF NOT EXISTS` 不会覆盖已有对象，也不会将旧表升级成这里的结构。若库或表已存在，应先只读检查 SHOW CREATE 结果，再单独准备迁移脚本。该初始化文件暂作为手工脚本，未注册为 Flyway 迁移，应用不会自动执行它。
