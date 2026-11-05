# 玄成科技接单系统后端

基于 RuoYi / Spring Boot 改造，负责订单、时间线、未读提醒、状态流转、客户账号、成员与权限、回收站、操作日志、导出和微信推送，为管理后台和客户端提供 `/jiedan/**` 接口。

## 技术栈

Java 17 · Spring Boot / RuoYi · Maven 多模块 · MyBatis / MySQL · Redis · Spring Security（令牌鉴权）

## 角色与权限

| 角色 | 角色标识 | 能做什么 |
| --- | --- | --- |
| 总管理员 | `admin` | 看全部项目、管理成员和客户账号、查看操作日志、彻底删除回收站里的项目 |
| 下属管理员 | `employee` | 只看负责人是自己的项目，只管归属自己的客户；看不到总管理员的项目 |
| 客户 | `customer` | 只看自己的项目；只能撤回还在“待确认”的问题 |

权限判定集中在 `JiedanAccess`，身份一律取自登录令牌，不信任前端传来的任何“我是谁”。

## 文档

| 文档 | 内容 |
| --- | --- |
| [deploy/production.md](deploy/production.md) | 线上目录、服务、发布流程 |
| [deploy/权限升级清单.md](deploy/权限升级清单.md) | 把线上升级为“总管理员 / 下属管理员”的步骤 |
| [deploy/补丁包升级清单.md](deploy/补丁包升级清单.md) | 上线前补丁包（回收站、操作日志、改密码、导出、备份等）的升级步骤与验收 |
| `deploy/fresh-install.sql` | **全新、空库**的初始化脚本（含 DROP TABLE，禁止在有数据的库上执行） |
| `deploy/upgrade_*.sql` | 已有数据库的升级脚本，可重复执行，执行前先备份 |
| `deploy/backup.sh` | 自动备份脚本（数据库 + 附件 + 清理 + 恢复演练） |
| `deploy/examples/` | Nginx / systemd / 数据库配置的**示例**，线上实际配置以 production.md 为准 |

## 本机启动

```bash
# 1. 准备 MySQL 8 与 Redis，建库后导入 deploy/fresh-install.sql
#    脚本里没有任何登录账号：需要自己插入一个 admin 角色的用户（密码存 BCrypt 哈希，别把密码写进仓库）。
#    测试用的演示账号只在 tests/e2e/seed.sql 里，不能带到线上。
# 2. 数据库、Redis 连接见 ruoyi-admin/src/main/resources/application-druid.yml / application.yml
#    （命令行可用 --spring.datasource.druid.master.url=... --spring.redis.database=N 覆盖）
mvn -B clean package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

默认端口 `8080`。管理后台和客户端的开发服务器通过 Vite 代理访问 `/jiedan/**`、`/login`、`/captchaImage`。

## 测试

`tests/e2e` 是端到端测试：自动建临时库、起后端、跑“权限隔离”和“上线补丁包”两套检查，结束后全部清理。需要一个**空的** Redis 库编号（避免污染本机其他项目的缓存）。详见 [tests/e2e/README.md](tests/e2e/README.md)。

```bash
MYSQL_PWD=<本机MySQL密码> E2E_REDIS_DB=10 bash tests/e2e/run.sh
```

## 当前部署

- 管理后台：https://jiedanxt.oksja.cn/
- 客户端：https://jiedanxt.oksja.cn/client/
- 后端由同域名 `/jiedan/**`、`/login`、`/captchaImage` 代理，服务监听 `127.0.0.1:18091`。
- GitHub Actions 只负责构建并保存产物，不自动部署。发布操作见 `deploy/production.md`。
- 正式管理员密码单独交付；客户账号由管理员在后台「客户账号」中创建。线上没有预置演示数据。

## 新电脑协作开发

默认 `main` 已包含当前开发版本，直接克隆即可，无需切换旧功能分支：

```powershell
git clone https://github.com/jiangyi3265/xuancheng-order-backend.git
git clone https://github.com/jiangyi3265/xuancheng-order-admin.git
git clone https://github.com/jiangyi3265/xuancheng-order-app.git
```

准备 Git、Node.js 22、JDK 17、Maven 3.9、MySQL 8 和 Redis，并把命令加入 PATH。前端分别在各自目录执行 `npm ci`、`npm run dev`，管理端为 http://localhost:5173，客户端为 http://localhost:5174。

后端按上面的“本机启动”初始化独立开发库并启动。`fresh-install.sql` 已包含当前表结构，新建库不需要再执行旧版升级脚本。开发数据库和 Redis 必须与生产环境隔离；生产数据、附件和私密配置不会随 Git 克隆。

Windows PowerShell 可以通过环境变量覆盖本机配置，不用修改仓库文件：

```powershell
$env:SPRING_DATASOURCE_DRUID_MASTER_URL = 'jdbc:mysql://localhost:3306/xc_jiedan?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8'
$env:SPRING_DATASOURCE_DRUID_MASTER_USERNAME = 'root'
$env:SPRING_DATASOURCE_DRUID_MASTER_PASSWORD = '<本机数据库密码>'
$env:SPRING_REDIS_HOST = 'localhost'
$env:SPRING_REDIS_PORT = '6379'
$env:SPRING_REDIS_DATABASE = '0' # 使用独立 Redis 或空闲库
$env:RUOYI_PROFILE = "$PWD/local-uploads"
mvn -B package
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

Redis 如果有密码，另设 `SPRING_REDIS_PASSWORD`。如后端端口调整，前端 PowerShell 设置 `$env:XC_BACKEND = 'http://localhost:18091'` 后再运行 `npm run dev`。

协作时先 `git pull --ff-only`，然后 `git switch -c feat/你的功能名`。提交后推送功能分支并通过 PR 合并 main。AI 流水线是独立项目，接单系统日常开发不依赖它。

仅在全新、可丢弃的本地开发库中，可以在导入 fresh-install.sql 后再导入 tests/e2e/seed.sql，获得测试账号 admin / admin123、cust_a / customer123。该脚本含模拟订单与测试设置，严禁导入生产库。使用 MySQL 客户端导入（从后端仓库目录启动）：

```text
mysql -u root -p
CREATE DATABASE xc_jiedan CHARACTER SET utf8mb4;
USE xc_jiedan;
SOURCE deploy/fresh-install.sql;
SOURCE tests/e2e/seed.sql;
```
