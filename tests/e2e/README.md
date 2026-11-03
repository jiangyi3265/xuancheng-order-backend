# 端到端测试

用真实的后端 + 真实的 MySQL/Redis 跑一遍关键流程，防止以后改代码时悄悄把权限、数据安全改坏。

## 怎么跑

前提：本机有 MySQL 8、Redis、Java 17、Node 18+，并且已经执行过 `mvn -B clean package -DskipTests`。

```bash
# 在 xuancheng-order-backend 目录
MYSQL_PWD=<本机MySQL密码> E2E_REDIS_DB=10 bash tests/e2e/run.sh
```

`E2E_REDIS_DB` 必须是一个**空的** Redis 库编号。脚本启动前会检查，不是空的就拒绝运行——本机的 Redis 常被其他项目共用，RuoYi 启动时会往里写缓存键，同名键会覆盖别人的内容。
结束时脚本只清理 RuoYi 自己写的键（`login_tokens:*`、`sys_config:*`、`sys_dict:*` 等），遇到不认识的键就不动它。

可选环境变量：`E2E_MYSQL_BIN`（mysql 路径）、`E2E_MYSQL_USER`、`E2E_JAVA`、`E2E_JAR`、`E2E_PORT`（默认 18091，别用 8080）。
Windows + Git Bash 下如果设置了 `HTTP_PROXY`，脚本里的等待逻辑已用 `curl --noproxy` 规避。

## 脚本做了什么

1. 建临时库 `xc_e2e_<进程号>`，导入 `deploy/fresh-install.sql` 和 `tests/e2e/seed.sql`（模拟旧版数据：订单负责人是写死的 1/2、两个客户账号）。
2. 起后端（临时库 + 指定的 Redis 库 + 临时上传目录）。
3. 依次运行：
   - `permission.test.mjs`：总管理员 / 下属管理员 / 客户的数据隔离、转派、客户归属、停用与改密踢下线、旧订单负责人迁移脚本。
   - `hardening.test.mjs`：变更戳、回收站（软删除/恢复/彻底删除/原负责人停用后的恢复）、客户撤回限制、操作日志（含日志表故障时业务不受影响）、改密码、Excel 导出（含权限范围与筛选）、附件安全头与上传鉴权、Druid 监控页关闭。
4. 删临时库、清 Redis、删临时文件。失败时后端日志会留在 `tests/e2e/last-backend.log`。

## 升级演练模式（上线前必跑）

默认模式用的是**新版**建库脚本，测不到“线上那个旧库按清单升级”这条路。上线前用旧版建库脚本再跑一遍：

```bash
# 取出线上现状对应的旧建库脚本（例：2026-10-29 上线的提交 9489439）
git show 9489439:deploy/fresh-install.sql > /tmp/old-fresh-install.sql
MYSQL_PWD=<本机MySQL密码> E2E_REDIS_DB=10 E2E_OLD_SCHEMA_SQL=/tmp/old-fresh-install.sql bash tests/e2e/run.sh
```

脚本会依次：旧结构建库 → 导入 `seed.sql` 旧数据 → 按清单顺序执行 `upgrade_permission_v1.sql`、`upgrade_hardening_v1.sql`（各跑两遍，验证可重复执行）→ 核对升级前后订单/时间线/Bug/账号/设置的数据指纹完全一致 → 与 `fresh-install.sql` 新建出来的库逐项比对表、列、索引、employee 角色 → 再起新后端跑全部检查。任何一步不一致就会中止并打印差异。

## 注意

- 测试库每次都是新建的；`permission.test.mjs` 不能在用过的库上重复跑（它要演示一次性的迁移脚本）。
- 想看某个检查为什么失败：失败行后面会打印服务端返回的内容。
