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

## 权限升级（总管理员 / 下属管理员）

升级前线上只有一个共用的 `admin` 账号，“总裁/副总裁”只是页面上的切换按钮，订单负责人 `owner_id` 存的是写死的 1、2。升级后每个人用自己的账号登录：总管理员（`admin` 角色）看全部项目，下属管理员（`employee` 角色）只看负责人是自己的项目，客户提交的需求自动落到其归属管理员名下。

一次性升级步骤（线上已有数据，**不要**重新导入 `fresh-install.sql`）：

1. `mysqldump --defaults-extra-file=/opt/xuancheng-order/config/mysql-root.cnf --single-transaction xc_jiedan` 备份。
2. 执行 `deploy/upgrade_permission_v1.sql`（建 `jiedan_account_owner` 表、加 `employee` 角色、给现有客户回填归属=总管理员；可重复执行）。
3. 按“发布更新”流程部署新版本的后端 JAR、管理后台、客户端。
4. 用总管理员登录后台，在「成员管理」创建下属账号（例如原来的副总裁）。
5. 把 `deploy/upgrade_permission_v1_owner_remap.sql` 最后一行 `CALL` 里的账号名改成这个下属的登录账号，执行**一次**：旧订单 owner_id 1→总管理员、2→该下属，清空旧的未读标记，推送 SendKey 迁到新账号。脚本带防重复标记，重复执行会报错中止。

第 3 步到第 5 步之间，总管理员能看到全部订单，但旧订单的“负责人”显示可能不对；下属账号还没创建，所以不会出现越权。新装的库用 `fresh-install.sql` 即可，已包含新表和角色。

## 上线前补丁包（回收站 / 操作日志 / 改密码 / 导出 / 自动备份）

在权限升级之上增加：项目删除先进回收站、操作日志、修改密码、订单筛选/分页/导出 Excel、附件安全头，并**堵住匿名上传漏洞**（旧版 `POST /jiedan/media/upload` 不需要登录）。一次性升级步骤：

1. 备份数据库（同上）。
2. 执行 `deploy/upgrade_hardening_v1.sql`（给 `jiedan_order` 加回收站字段、建 `jiedan_audit_log` 表；可重复执行）。
3. 按“发布更新”流程部署新版本的后端 JAR、管理后台、客户端。
4. Nginx 的 `client_max_body_size` 不小于 `55m`；生产配置里如写了 `max-file-size`，改为 `50MB`。
5. 安装 `deploy/backup.sh` 并加计划任务（见下一节）。

完整的确认项、验收清单和回滚注意事项见 [补丁包升级清单.md](补丁包升级清单.md)。新装的库用 `fresh-install.sql` 即可，已包含这些结构。

## 备份与恢复

`deploy/backup.sh` 每天做三件事：数据库压缩备份并校验、附件增量镜像（附件文件名随机且不会被修改，只需追加）、清理旧备份（日备份留 30 天，每月 1 号的留 1 年）。设置 `OFFSITE_DIR` 会把新备份同步到另一块盘 / 挂载的网盘；设置 `VERIFY_RESTORE=1` 会把当天备份导入临时库验证后再删除（建议每周一次）。部署方法和计划任务见脚本开头注释与补丁包清单第五步。

备份和线上在同一块磁盘上只能防误操作，防不了磁盘损坏——请务必设置异地目录。

**恢复数据库**（会覆盖线上数据，先确认要恢复到哪一份）：

```bash
systemctl stop xuancheng-order-backend
gunzip -c /opt/xuancheng-order/backups/db_<日期_时分>.sql.gz \
  | mysql --defaults-extra-file=/opt/xuancheng-order/config/mysql-root.cnf xc_jiedan
systemctl start xuancheng-order-backend
```

**找回误删的附件**：`cp -a -n /opt/xuancheng-order/backups/uploads-mirror/. <上传目录>/`（`-n` 表示不覆盖已有文件）。

## HTTPS 与 Nginx

站点配置：`/www/server/panel/vhost/nginx/jiedanxt.oksja.cn.conf`。
证书：`/etc/letsencrypt/live/xuancheng-order/`，Certbot 使用原有账户自动续期；续期 hook 检查并重载 Nginx。
HTTP 自动跳转 HTTPS，`/.well-known/acme-challenge/` 使用原站点目录处理证书验证。
已停用 AI 路由，`/jiedan/ai` 返回 404。

## 验收范围

管理员和客户登录、客户端提交需求、后台收到订单、双向留言、Bug 创建与追加、订单状态同步、附件上传下载、客户间隔离、客户禁止访问管理接口、内部笔记不向客户泄露；匿名不能上传附件；删除进回收站可恢复；操作日志有记录；当天有数据库备份。没有配置微信推送密钥，外部微信推送保持关闭。
