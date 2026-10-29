# 接单系统生产部署

当前管理后台： https://jiedanxt.oksja.cn/
当前客户端： https://jiedanxt.oksja.cn/client/

## 目录和服务

部署根目录 `/opt/xuancheng-order`。`current` 指向当前版本目录，其下 `admin/` 为后台静态文件，`admin/client/` 为客户端，`backend/ruoyi-admin.jar` 为后端。持久数据在 `data/`、`uploads/`；配置在 `config/`；日志在 `logs/`；备份在 `backups/`。

systemd 服务：`xuancheng-order-mysql`、`xuancheng-order-redis`、`xuancheng-order-backend`，均开机自启。
MySQL 只监听 `127.0.0.1:13391`；Redis 只监听 `127.0.0.1:16391`；Java API 只监听 `127.0.0.1:18091`。
独立系统用户 `xuancheng-order` 运行这些服务。Redis 使用 `bin/redis-server` 的本地副本，不修改服务器其他 Redis 实例。

Java 17 路径为 `/www/server/java/jdk-17.0.8/bin/java`。启动参数包含 `--spring.profiles.active=druid,production`；生产配置来自 `config/application-production.yml`；日志用 JVM 参数 `-Dlogback.configurationFile=/opt/xuancheng-order/config/logback.xml` 和 Spring 参数 `--logging.config=file:/opt/xuancheng-order/config/logback.xml` 指向独立日志目录。

## 首次安装和数据

`fresh-install.sql` 仅用于全新、空的 `xc_jiedan` 数据库，含系统配置，不含演示订单、客户和登录记录。脚本含 DROP TABLE，禁止在已有业务数据的库上执行。管理员账号以单独生成的 BCrypt 密码创建，密码不写入仓库。
生产数据库及 Redis 密码保存在服务器受权限保护的配置目录中。`mysql-root.cnf` 是本实例的数据库维护凭据，仅 root 可读。

## 发布更新

1. 管理后台执行 `npm ci && npm run build`；客户端执行 `npm ci` 后设置环境变量 `VITE_BASE=/client/` 执行 `npm run build`；后端执行 `mvn -B clean package`。
2. 在 `releases/` 下创建新的版本目录，放入两个前端构建产物及完整后端 JAR。不得覆盖 `data/`、`uploads/`、`config/`。
3. 先使用 `mysqldump --defaults-extra-file=/opt/xuancheng-order/config/mysql-root.cnf --single-transaction xc_jiedan` 备份数据库。已有数据库不重新导入初始化 SQL。
4. 将 `current` 原子切换到新版本，重启 `xuancheng-order-backend`。`nginx -t` 通过后重载 Nginx。
5. 检查 `/captchaImage`、后台登录、客户提交与订单查询。失败则切回旧版本并重启后端。

## HTTPS 与 Nginx

站点配置：`/www/server/panel/vhost/nginx/jiedanxt.oksja.cn.conf`。
证书：`/etc/letsencrypt/live/xuancheng-order/`，Certbot 使用原有账户自动续期；续期 hook 检查并重载 Nginx。
HTTP 自动跳转 HTTPS，`/.well-known/acme-challenge/` 使用原站点目录处理证书验证。
已停用 AI 路由，`/jiedan/ai` 返回 404。

## 验收范围

管理员和客户登录、客户端提交需求、后台收到订单、双向留言、Bug 创建与追加、订单状态同步、附件上传下载、客户间隔离、客户禁止访问管理接口、内部笔记不向客户泄露。没有配置微信推送密钥，外部微信推送保持关闭。
