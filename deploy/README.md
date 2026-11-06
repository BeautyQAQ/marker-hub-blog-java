# NAS 部署与临时验收

前端使用 Node 24 构建后由 Nginx 提供静态资源；后端使用 Maven / JDK 21 构建、JRE 21 运行。两个 Dockerfile 均执行现有测试；NAS 不需要安装 Java、Maven 或 Node。

## 文件布局与运行条件

```text
marker-hub/
  marker-hub-blog-java/
    Dockerfile
    deploy/compose.yaml
    deploy/compose.smoke.yaml
    deploy/smoke.sh
  marker-hub-blog-vue/
    Dockerfile
    deploy/nginx.conf
```

要求 Linux Docker Engine、Compose 2.24+、Buildx，以及能拉取基础镜像和访问 Maven Central / npm 的网络。自动临时验收还使用主机上的 sh、curl、openssl。已在 x86_64 NAS 的 Docker 28.5.2、Compose 2.40.3 环境验证；其他架构需重新构建验收。

生产 Compose 仅创建 api、web 和应用网络，连接现有 NAS MySQL / Redis。不会自动初始化数据库、创建账号、清理 Redis 或改变已有数据。两个仓库需并列放置，也可以通过 BLOG_WEB_CONTEXT 修改前端构建路径。

## 生产构建和启动

以下命令均在后端的 deploy 目录执行：

```sh
cp .env.example .env
```

编辑 .env：BLOG_HTTP_PORT 为 NAS 上的空闲端口；BLOG_ALLOWED_ORIGINS 填浏览器实际访问的来源，包含协议和端口，多个来源用逗号分隔，末尾不加斜线。域名访问示例：BLOG_ALLOWED_ORIGINS=https://blog.example.com。

默认使用 application.yml 中已有的 NAS 数据库、Redis 和 JWT 配置，保留项目既有配置方式。需要覆盖时，复制 .env.runtime.example 为 .env.runtime，填写需要覆盖的字段即可；两个本地配置文件都已加入 Git 忽略。镜像中包含应用配置，镜像应留在受控主机或私有仓库。

```sh
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 180
docker compose ps
curl --fail http://127.0.0.1:18080/healthz
curl --fail http://127.0.0.1:18080/api/health/ready
```

默认只绑定 NAS 的 127.0.0.1:18080。局域网直接访问时，将 BLOG_BIND_IP 设为 NAS 的局域网地址，并将 http://该地址:18080 加入允许来源。后端 8080、数据库和 Redis 不通过此 Compose 发布端口。

空库首次使用：先按照 ../sql/README.md 初始化，再显式创建站长。已有账号无需再次创建；初始化拒绝覆盖同名账号。

```sh
docker compose run --rm api --spring.profiles.active=bootstrap-admin
```

该命令交互读取密码；无交互环境可以通过标准输入传递一行，不要把密码写进命令行参数。

## HTTPS 与反向代理

容器内 Nginx 监听 8080，负责 /api/ 转发、移除 /api 前缀、Vue history 路由回退、静态资源缓存和 gzip。生产 HTTPS 由 NAS 现有反向代理终止，将整个站点转发到 http://127.0.0.1:18080。TLS 证书及公网域名不包含在此次内网临时测试中。

保留浏览器原始 Host / Origin，并在 BLOG_ALLOWED_ORIGINS 中填写最终 HTTPS 来源。错误的来源会得到 403；Vite 开发代理不参与生产部署。直接刷新 /blog/123、/my/blogs 等路由应返回 index.html；缺失 /assets 文件返回 404，API 错误保持 JSON。

Nginx 会覆盖客户端伪造的转发头；后端启用 Tomcat native 转发头处理，仅通过应用网络接受请求。直接访问此 Nginx 时，后端可取得客户端 IP。若外层还有 NAS 反向代理，当前限流看到的是外层代理 IP；要按最终访客 IP 限流，应在 Nginx 中仅为已确认的外层代理地址配置 set_real_ip_from / real_ip_header，再验收，不能直接信任任意来源的 X-Forwarded-For。

## 健康检查和运行维护

| 地址 | 行为 |
| --- | --- |
| /healthz（前端） | Nginx 存活，200，{"status":"UP"} |
| /api/health/live | JVM / HTTP 服务存活；不访问数据库和 Redis |
| /api/health/ready | 执行只读 SELECT 1 与 Redis PING；均成功返回 200，否则 503 / DOWN |

健康接口匿名可读，忽略过期 Authorization，不暴露连接配置或错误详情。后端容器检查 readiness，web 等待 api 首次健康后启动；Nginx 会重新解析被替换后端的容器地址。

容器以非 root 用户、只读根文件系统运行，临时写入 /tmp。api 内存上限为 1 GiB，web 为 128 MiB、2 个 Nginx worker，可按 NAS 容量调整。标准输出日志每容器最多 3 × 10 MiB。restart: unless-stopped 会在进程退出或主机重启后恢复服务；Docker 标记 unhealthy 本身不会触发重启，应先检查依赖和日志。

```sh
docker compose logs --tail 100 api web
docker compose restart api
docker compose stop
docker compose start
```

请勿使用 docker compose config 的完整输出对外分享运行配置，使用 --quiet 校验即可。现有数据库的备份与恢复仍由 NAS 数据服务负责，应用 Compose 不包含数据库卷。

## 版本更新和回滚

建议把 BLOG_API_IMAGE / BLOG_WEB_IMAGE 改为各自 Git 提交号对应的唯一标签，记录两端版本组合；基础镜像使用主版本标签，需完全复现时额外记录实际 digest。当前不自动发布到镜像仓库。

```sh
# 修改 .env 的两个镜像标签后构建、部署。
docker compose build
docker compose up -d --wait --wait-timeout 180
```

保留上一版镜像。回滚时恢复上一版两个标签，运行 docker compose up -d --no-build --pull never --wait。应用回滚不回滚数据库；未来有结构变更时须单独保证迁移兼容并先备份。

## 独立临时测试与自动清理

```sh
# 始终使用新的项目名。可选：指定一个未占用的 BLOG_HTTP_PORT。
BLOG_SMOKE_PROJECT=marker-hub-smoke-20261106-example sh smoke.sh
```

脚本创建独立 Buildx builder、临时 MySQL 8.4、Redis、数据库卷、随机密码和临时管理员。所有数据连接均覆盖到该独立环境，不使用 NAS 已有 marker-hub 数据库或共享 Redis。依赖位于 internal 网络，Web 额外连接入口网络以发布 NAS loopback 端口；不需要现有站长密码。MySQL 通过 TCP 检查最终服务就绪，避免初始化阶段临时 socket 服务过早报健康。

验收内容包括：镜像内测试与构建、健康检查、Nginx 静态资源及缓存、history 回退、API 前缀和错误边界、允许/拒绝来源、登录、草稿保密、发布编辑、软删除、退出令牌撤销、API 容器替换、数据库/Redis 中断和恢复时的健康状态，以及数据库记录在依赖重启后仍然保留。

已提前构建好本次项目名对应的 `$项目名-api:test` 和 `$项目名-web:test` 镜像时，可设置 BLOG_SMOKE_BUILD=0 跳过重复构建；仍会创建全新测试数据环境并清理这些专用测试镜像。

脚本通过 trap 在成功或失败退出时执行 down --volumes，删除本次应用镜像、独立 builder 和构建缓存、临时密码文件，并核对项目的容器/网络/卷均已消失。本次新增拉取的测试依赖镜像仅尝试普通 image rm；若被其他容器占用则保留，不使用强制删除或全局 prune。脚本保留源码目录，远程传输的临时源码由操作者在收集日志后删除。

若进程被 SIGKILL、主机断电，trap 无法执行；恢复后按本次确切项目名清理，不能替换为生产项目名：

```sh
docker compose -p 本次marker-hub-smoke项目名 -f compose.yaml -f compose.smoke.yaml down --volumes --remove-orphans
docker buildx rm 本次marker-hub-smoke项目名
docker ps -a --filter label=com.docker.compose.project=本次marker-hub-smoke项目名
docker network ls --filter label=com.docker.compose.project=本次marker-hub-smoke项目名
docker volume ls --filter label=com.docker.compose.project=本次marker-hub-smoke项目名
```

Compose 解析 smoke 配置需要临时密码变量；若原临时文件已不可用，可给 SMOKE_DB_PASSWORD / SMOKE_JWT_SECRET 设置任意非空占位值用于 down，切勿运行 up。

对于需要保留的正式环境，可用 Node 24 执行 ../scripts/smoke-deploy.mjs，并设置 BLOG_SITE_URL、BLOG_SMOKE_USERNAME、BLOG_SMOKE_PASSWORD。这会创建后软删除测试文章，仍留下数据库记录；完整物理清理由上述独立测试环境销毁实现。

## 2026-11-06 NAS 验收记录

已在上述 NAS 上完成镜像构建，前端 39 项、后端 40 项测试通过。最终独立部署验收退出码为 0，涵盖本节列出的站点/API、认证、文章操作、API 替换、数据库/Redis 故障恢复和数据保留检查。MySQL 的初始化就绪探测与内部网络发布端口问题已修正后回归通过。

测试结束后，已删除临时容器、数据库卷、测试网络、两端测试镜像、独立 builder / 缓存、本次新拉取的依赖镜像及远程源码目录，并核对测试端口关闭；现有 NAS 服务保留。完整推进记录见工作区根目录 PROJECT_REVIEW.md。第一轮覆盖 HTTP/API 内网部署验收，随后补齐了下述浏览器验收；未覆盖公网 HTTPS 或 CI 浏览器回归套件。

同日首次追加 Computer Use 浏览器验收时，项目 marker-hub-browser-20261106-2fb709 部署健康，但工具无法可靠识别 Windows 浏览器 URL，验收中止并清理。随后重新建立独立项目 marker-hub-browser-20261106-31d946，在 Edge 站点读取仍超时后改用 Codex 内置浏览器，实际完成登录/错误密码、管理页守卫与返回、Markdown 写作预览、未保存提醒、草稿保密和持久化、发布与详情刷新、正文编辑、删除取消/确认、退出和页面故障重试验收。只停止临时 API 时，页面显示服务不可用；恢复后点击“重新加载”可恢复文章列表。前端 39 项、后端 40 项测试与双端构建再次通过。

复测环境也已完整清理：4 个服务、数据库卷、2 个网络、应用镜像、独立 builder / 缓存、新增依赖镜像、远程临时目录和本地传输包均已删除；项目资源标签查询为空，18089 无监听，镜像 ID 集合恢复测试前基线，原有 11 个容器均在运行。页面截图、逐项验收结果和清理摘要在工作区根目录 browser-acceptance/README.md。此次通过的是内置浏览器的内网核心流程；Edge 完整流程、跨浏览器、移动端专项和 CI 回归仍需另行覆盖。

## 依据

- [Compose 健康依赖与启动顺序](https://docs.docker.com/compose/how-tos/startup-order/)
- [Compose 服务、日志和运行参数](https://docs.docker.com/reference/compose-file/services/)
- [Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)
- [Spring Boot 转发头处理](https://docs.spring.io/spring-boot/how-to/webserver.html)
