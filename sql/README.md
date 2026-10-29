# 数据库初始化

数据库名称固定为 `marker-hub`，目标是 dbx 中的 `nas-mysql` 测试连接。

在该连接的 SQL 编辑器中打开并整体执行 [001_init_marker_hub.sql](001_init_marker_hub.sql)。账号需要对目标数据库拥有建库、建表及创建外键所需权限。脚本仅创建数据库和两张表，不创建数据库账号、不导入数据，也不配置应用连接。

2026-10-29 用户手工执行脚本后，已通过更新后的 DBX HTTP MCP 对 `nas-mysql` 完成只读验收：

- 版本：MySQL Community Server 8.1.0。
- `marker-hub` 数据库及 `m_user` / `m_blog` 表均已创建，最初验收时两张表各有 0 条记录；后续账号初始化及业务写入会改变数量。
- SHOW CREATE 确认两表字段、默认值、用户名唯一索引、文章列表索引和作者外键均与初始化脚本一致。
- InnoDB 为默认存储引擎，`utf8mb4_unicode_ci` 排序规则存在。
- 当前连接具有全局 CREATE、REFERENCES、INDEX 等相关权限，并具有 SHOW DATABASES 权限。
- 会话时区为 SYSTEM，当前 UTC 偏移为 +08:00；服务器默认排序规则为 `utf8mb4_0900_ai_ci`，本脚本显式选用 `utf8mb4_unicode_ci`。

现有 MCP 配置已改为 HTTP `/mcp` 入口并配置 Authorization。已成功初始化 MCP 会话并调用 dbx 工具进行验收。本次未修改 MCP 配置、数据库结构、数据或访问令牌。

后端数据源使用本机可访问的 `192.168.20.5:3306/marker-hub`，凭据按用户要求保存在 application.yml；代码生成器读取同一份配置。JDK 21 升级后已使用 Connector/J 9.7.0 加载实际配置完成只读 JDBC 验证：DATABASE() 返回 marker-hub，会话 time_zone 为 +00:00，用户 8 个字段和文章 7 个字段查询均通过；实际可执行 jar 启动及用户查询也通过。未运行代码生成器。

业务接口实现后，已用显式 bootstrap-admin 命令在该测试库创建用户指定的 `admin`，确认只存 BCrypt 摘要。实际服务及浏览器使用 NAS MySQL 和 DBX 的 `nas-redis`（Redis 8.6.2）完成登录、草稿保密、发布、编辑、删除及令牌撤销验收。本次创建的两条验收文章在验证软删除后，按精确 ID、作者和验收标题限定清理；验收结束时保留 1 个用户、0 篇文章，Redis 登录令牌许可记录为 0。

| 表 | 对应实体 | 约束与索引 |
| --- | --- | --- |
| `m_user` | `com.blog.entity.User` | BIGINT 自增主键；username 唯一；status 非空 |
| `m_blog` | `com.blog.entity.Blog` | BIGINT 自增主键；作者外键；公开列表和作者列表索引 |

字段名保持现有映射：`userId -> user_id`、`lastLogin -> last_login`。id 与 user_id 使用有符号 BIGINT，status 使用有符号 TINYINT，兼容 Java Long 和锁定值 -1。用户名唯一约束在所选排序规则下不区分大小写。

用户状态为 0 正常、-1 锁定，非正常账号不能登录或继续使用令牌。文章状态为 0 草稿、1 已发布、-1 已删除。后端实现状态校验：公开列表只返回已发布文章，草稿详情仅作者可读；新文章未指定状态时保存为草稿，发布必须显式设置 1，更新未指定状态时保持原状态。删除为软删除，删除后不能通过编辑恢复。数据库不会自动实现这些业务规则。

创建文章前需要先有合法用户，外键会拒绝不存在的作者；删除仍有关联文章的用户也会被拒绝。脚本不预置用户，站长账号通过后端显式初始化命令创建，操作方法见项目 README。password 列保存 BCrypt 摘要，不存明文，普通应用启动不会自动建号或覆盖密码。

时间字段使用 DATETIME，约定保存 UTC。DBX 的默认会话为 UTC+8；应用的 JDBC URL 已同时设置 serverTimezone=UTC 和编码后的 sessionVariables，使每条 JDBC 连接的会话时区为 +00:00。初始化脚本的 SET SESSION 仅影响执行脚本的会话，未修改服务器全局时区。

`CREATE ... IF NOT EXISTS` 不会覆盖已有对象，也不会将旧表升级成这里的结构。若库或表已存在，应先只读检查 SHOW CREATE 结果，再单独准备迁移脚本。该初始化文件暂作为手工脚本，未注册为 Flyway 迁移，应用不会自动执行它。
