#!/usr/bin/env bash
# 一键端到端测试：建临时库 -> 起后端 -> 跑权限、补丁包、记单收款三套测试 -> 全部清理。
#
# 必填环境变量：
#   E2E_REDIS_DB     一个**空的** Redis 库编号（脚本启动前会检查，非空就拒绝运行，
#                    避免污染本机其他项目的缓存）
# 常用可选环境变量：
#   MYSQL_PWD        MySQL 密码（本机 root）
#   E2E_MYSQL_BIN    mysql 可执行文件（默认从 PATH 找）
#   E2E_MYSQL_USER   默认 root
#   E2E_JAVA         java 可执行文件（默认从 PATH 找，需要 17）
#   E2E_JAR          后端 jar（默认 ruoyi-admin/target/ruoyi-admin.jar，需先 mvn package）
#   E2E_PORT         后端端口，默认 18091（别用 8080，本机常被占）
#
# 用法（在 xuancheng-order-backend 目录）：
#   MYSQL_PWD=123456 E2E_REDIS_DB=10 bash tests/e2e/run.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MYSQL_BIN="${E2E_MYSQL_BIN:-mysql}"
MYSQL_USER="${E2E_MYSQL_USER:-root}"
JAVA_BIN="${E2E_JAVA:-java}"
JAR="${E2E_JAR:-$ROOT/ruoyi-admin/target/ruoyi-admin.jar}"
PORT="${E2E_PORT:-18091}"
REDIS_DB="${E2E_REDIS_DB:-}"
DB="xc_e2e_$$"

die() { echo "错误：$*" >&2; exit 2; }
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

[ -n "$REDIS_DB" ] || die "请用 E2E_REDIS_DB 指定一个空的 Redis 库编号，例如 E2E_REDIS_DB=10"
[ -f "$JAR" ] || die "找不到后端 jar：$JAR（先在 backend 目录执行 mvn -B package -DskipTests）"
MYSQL_BIN="$(command -v "$MYSQL_BIN")" || die "找不到 mysql 命令，请设置 E2E_MYSQL_BIN"
command -v redis-cli >/dev/null 2>&1 || die "找不到 redis-cli"

KEYS_BEFORE="$(redis-cli -n "$REDIS_DB" dbsize | tr -d '\r')"
[ "$KEYS_BEFORE" = "0" ] || die "Redis 库 $REDIS_DB 不是空的（有 $KEYS_BEFORE 个键），可能是别的项目在用，换一个空库"

PROFILE_DIR="$(mktemp -d)"
LOG="$PROFILE_DIR/backend.log"
JPID=""

stop_backend() {
  [ -n "$JPID" ] && kill "$JPID" 2>/dev/null
  if command -v taskkill >/dev/null 2>&1; then
    local wpid
    wpid="$(netstat -ano 2>/dev/null | grep ":$PORT " | grep LISTENING | awk '{print $5}' | head -1)"
    [ -n "$wpid" ] && taskkill //PID "$wpid" //F >/dev/null 2>&1
  fi
  return 0
}

cleanup() {
  stop_backend
  sleep 1
  "$MYSQL_BIN" -u"$MYSQL_USER" -e "drop database if exists \`$DB\`" >/dev/null 2>&1
  "$MYSQL_BIN" -u"$MYSQL_USER" -e "drop database if exists \`${DB}_ref\`" >/dev/null 2>&1
  # 只清理 RuoYi 自己写的那些键；如果出现不认识的键，说明有别的程序在用这个库，就不动它
  local stray=0 key
  while IFS= read -r key; do
    key="${key%$'\r'}"
    [ -z "$key" ] && continue
    case "$key" in
      login_tokens:*|sys_config:*|sys_dict:*|captcha_codes:*|pwd_err_cnt:*|rate_limit:*|repeat_submit:*) ;;
      *) stray=1 ;;
    esac
  done < <(redis-cli -n "$REDIS_DB" keys '*')
  if [ "$stray" = "0" ]; then
    # 不用 --scan：Windows 版 redis-cli 较老，不支持
    redis-cli -n "$REDIS_DB" keys '*' | tr -d '\r' | while IFS= read -r key; do
      [ -n "$key" ] && redis-cli -n "$REDIS_DB" del "$key" >/dev/null
    done
  else
    echo "提示：Redis 库 $REDIS_DB 里出现了不认识的键，未做清理，请手工检查" >&2
  fi
  # 失败时把后端日志留下来，方便排查（已被 .gitignore 忽略）
  [ "${RC:-1}" != "0" ] && [ -f "$LOG" ] && cp "$LOG" "$HERE/last-backend.log"
  rm -rf "$PROFILE_DIR"
}
trap cleanup EXIT

mysql_db() { "$MYSQL_BIN" -u"$MYSQL_USER" --default-character-set=utf8mb4 "$@"; }
new_db() { mysql_db -e "create database \`$1\` default character set utf8mb4 collate utf8mb4_general_ci" || die "建库失败（检查 MYSQL_PWD）"; }

# 表结构指纹：列（类型/可空/默认值/自增）、索引、表引擎与字符集，不含列顺序和注释
schema_fingerprint() {
  mysql_db -N -B -e "
    select 'COL', table_name, column_name, column_type, is_nullable, ifnull(column_default,'<NULL>'), extra
      from information_schema.columns where table_schema='$1' order by table_name, column_name;
    select 'IDX', table_name, index_name, non_unique, seq_in_index, column_name
      from information_schema.statistics where table_schema='$1' order by table_name, index_name, seq_in_index;
    select 'TAB', table_name, engine, table_collation
      from information_schema.tables where table_schema='$1' order by table_name;
    select 'ROLE', role_key, role_name, role_sort, data_scope, status, del_flag
      from \`$1\`.sys_role order by role_key;"
}

# 业务数据指纹：升级脚本只加结构，不该改动任何已有业务数据
data_fingerprint() {
  mysql_db -N -B "$DB" -e "
    set session group_concat_max_len = 10000000;
    select 'ORDERS', count(*), md5(group_concat(concat_ws('|',id,order_no,title,customer_account,amount,owner_id,status,unread,requirement) order by id)) from jiedan_order;
    select 'TIMELINE', count(*), md5(group_concat(concat_ws('|',id,order_id,user_name,type,content) order by id)) from jiedan_timeline;
    select 'BUG', count(*), md5(group_concat(concat_ws('|',id,order_id,content,status,created_by) order by id)) from jiedan_bug;
    select 'USER', count(*), md5(group_concat(concat_ws('|',user_id,user_name,password,status,del_flag) order by user_id)) from sys_user;
    select 'USER_ROLE', count(*), md5(group_concat(concat_ws('|',user_id,role_id) order by user_id, role_id)) from sys_user_role;
    select 'SETTING', count(*), md5(group_concat(concat_ws('|',setting_key,setting_value) order by setting_key)) from jiedan_setting;"
}

if [ -n "${E2E_OLD_SCHEMA_SQL:-}" ]; then
  # 升级演练：旧版库（线上现状）+ 旧版数据 -> 按上线清单的顺序执行两个升级脚本 -> 再跑全部测试
  [ -f "$E2E_OLD_SCHEMA_SQL" ] || die "找不到旧版建库脚本：$E2E_OLD_SCHEMA_SQL"
  echo ">> 升级演练：旧结构($E2E_OLD_SCHEMA_SQL) + seed.sql 旧数据 -> upgrade_permission_v1 -> upgrade_hardening_v1 -> upgrade_solo_v1"
  new_db "$DB"
  mysql_db "$DB" < "$E2E_OLD_SCHEMA_SQL" || die "导入旧版建库脚本失败"
  mysql_db "$DB" < "$HERE/seed.sql" || die "导入 seed.sql 失败"
  # 模拟线上旧版里“标记过已收款”的订单（旧版没有入口，但数据库里允许），用来检查升级会补录收款流水
  mysql_db "$DB" -e "update jiedan_order set paid=1 where id=4" || die "准备旧版已收款数据失败"
  BEFORE="$(data_fingerprint)"
  for round in 1 2; do
    mysql_db "$DB" < "$ROOT/deploy/upgrade_permission_v1.sql" || die "upgrade_permission_v1.sql 第 $round 次执行失败"
    mysql_db "$DB" < "$ROOT/deploy/upgrade_hardening_v1.sql" || die "upgrade_hardening_v1.sql 第 $round 次执行失败"
    mysql_db "$DB" < "$ROOT/deploy/upgrade_solo_v1.sql" || die "upgrade_solo_v1.sql 第 $round 次执行失败"
  done
  echo "   三个升级脚本各执行两遍（含幂等性）：成功"
  AFTER="$(data_fingerprint)"
  if [ "$BEFORE" != "$AFTER" ]; then
    echo "错误：升级脚本改动了已有业务数据" >&2
    diff <(echo "$BEFORE") <(echo "$AFTER") >&2
    exit 4
  fi
  echo "   升级前后已有业务数据完全一致（订单/时间线/Bug/账号/角色关系/设置）"
  BACKFILL="$(mysql_db -N -B "$DB" -e "select (select count(*) from jiedan_payment where order_id=4 and note='升级前已标记收款'), (select concat(received_amount,'|',paid) from jiedan_order where id=4), (select count(*) from jiedan_payment), (select sum(received_amount) from jiedan_order where id<>4)")"
  EXPECT_BACKFILL="$(printf '1	4000.00|1	1	0.00')"
  if [ "$BACKFILL" != "$EXPECT_BACKFILL" ]; then
    echo "错误：升级对旧版已收款订单的收款流水补录不对（期望：1 条补录、订单4=4000.00|1、流水共 1 条、其余订单已收为 0），实际：$BACKFILL" >&2
    exit 4
  fi
  echo "   旧版标记过已收款的订单，升级后补录了整笔收款流水(仅 1 条、重复执行不会重复补)；其余订单未受影响"
  # 与全新安装的结构逐项比对，防止“升级出来的库”和“新装的库”悄悄不一样
  DB_REF="${DB}_ref"
  new_db "$DB_REF"
  mysql_db "$DB_REF" < "$ROOT/deploy/fresh-install.sql" || die "导入 fresh-install.sql 失败"
  DRIFT="$(diff <(schema_fingerprint "$DB") <(schema_fingerprint "$DB_REF"))"
  mysql_db -e "drop database if exists \`$DB_REF\`" >/dev/null 2>&1
  if [ -n "$DRIFT" ]; then
    echo "错误：升级后的库结构与 fresh-install.sql 不一致：" >&2
    echo "$DRIFT" >&2
    exit 4
  fi
  echo "   升级后的表结构、索引、employee 角色与 fresh-install.sql 完全一致"
else
  echo ">> 建临时库 $DB（fresh-install.sql + seed.sql）"
  new_db "$DB"
  mysql_db "$DB" < "$ROOT/deploy/fresh-install.sql" || die "导入 fresh-install.sql 失败"
  mysql_db "$DB" < "$HERE/seed.sql" || die "导入 seed.sql 失败"
fi

echo ">> 启动后端 :$PORT（Redis 库 $REDIS_DB）"
"$JAVA_BIN" -jar "$JAR" --server.port="$PORT" \
  "--spring.datasource.druid.master.url=jdbc:mysql://localhost:3306/$DB?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=GMT%2B8" \
  --spring.redis.database="$REDIS_DB" \
  "--ruoyi.profile=$(winpath "$PROFILE_DIR")" \
  --logging.level.com.ruoyi=info > "$LOG" 2>&1 &
JPID=$!

UP=0
for _ in $(seq 1 90); do
  sleep 2
  # --noproxy：本机若设了 HTTP_PROXY，curl 会把请求发给代理，端口没开时也会得到代理返回的 502（退出码 0）
  code="$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/captchaImage")"
  if [ "$code" = "200" ]; then UP=1; break; fi
done
if [ "$UP" != "1" ]; then
  echo "后端没有起来，日志末尾：" >&2
  tail -30 "$LOG" >&2
  exit 3
fi

export E2E_BASE="http://127.0.0.1:$PORT" E2E_DB="$DB" E2E_MYSQL="$(winpath "$MYSQL_BIN")" E2E_MYSQL_USER="$MYSQL_USER"
RC=0
for t in permission hardening solo; do
  echo
  echo "################ $t.test.mjs ################"
  node "$HERE/$t.test.mjs" || RC=1
done

echo
if [ "$RC" = "0" ]; then echo "全部通过"; else echo "有失败项，后端日志：$LOG（脚本退出后会被删除，需要的话请先复制）"; fi
exit $RC
