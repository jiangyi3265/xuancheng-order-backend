#!/usr/bin/env bash
# 上线前服务器体检（只读）。
#
# 只看不改：不改文件、不改数据库、不重启任何服务。唯一对外的动作是对本机后端发一个
# “不带文件的上传请求”，用来确认匿名上传漏洞的现状（没有文件，不会写入任何东西）。
#
# 用法（在服务器上，root）：
#   bash preflight.sh
# 把全部输出原样发回即可。路径和库名与 production.md 一致，不一致时用环境变量改：
#   XC_ROOT  XC_DB  XC_MYSQL_CNF  XC_PROD_YML  XC_NGINX_CONF  XC_API
#
# 输出里的 [通过] 表示没问题；[注意] 表示上线时要处理或留意；[失败] 表示上线前必须先解决。
# 脚本不会打印任何密码或密钥的内容。

set -u
ROOT="${XC_ROOT:-/opt/xuancheng-order}"
DB="${XC_DB:-xc_jiedan}"
MYSQL_CNF="${XC_MYSQL_CNF:-$ROOT/config/mysql-root.cnf}"
PROD_YML="${XC_PROD_YML:-$ROOT/config/application-production.yml}"
NGINX_CONF="${XC_NGINX_CONF:-/www/server/panel/vhost/nginx/jiedanxt.oksja.cn.conf}"
API="${XC_API:-http://127.0.0.1:18091}"

PASS=0; WARN=0; FAIL=0
pass() { echo "  [通过] $*"; PASS=$((PASS + 1)); }
warn() { echo "  [注意] $*"; WARN=$((WARN + 1)); }
fail() { echo "  [失败] $*"; FAIL=$((FAIL + 1)); }
info() { echo "         $*"; }
section() { echo; echo "== $* =="; }

# 只读查询；失败时返回空
q() {
  mysql --defaults-extra-file="$MYSQL_CNF" --default-character-set=utf8mb4 -N -B "$DB" -e "$1" 2>/dev/null
}

echo "接单系统 · 上线前服务器体检（只读） $(date '+%F %T')"

# ---------------------------------------------------------------
section "1. 当前部署"
if [ -L "$ROOT/current" ]; then
  CUR="$(readlink -f "$ROOT/current")"
  pass "current 指向 $CUR   <- 回滚时要切回这个目录，请记下"
  info "目录属主：$(stat -c '%U:%G' "$CUR" 2>/dev/null)"
  info "内容：$(ls "$CUR" 2>/dev/null | tr '\n' ' ')"
  [ -f "$CUR/backend/ruoyi-admin.jar" ] && info "旧后端 jar：$(ls -l --time-style=long-iso "$CUR/backend/ruoyi-admin.jar" | awk '{print $5" 字节, "$6" "$7}')" \
    || warn "$CUR/backend/ruoyi-admin.jar 不存在，目录结构和手册假设的不一样，先别动手"
else
  fail "$ROOT/current 不是软链接（手册假设它是）。实际情况：$(ls -ld "$ROOT/current" 2>&1)"
fi
info "已有版本目录：$(ls "$ROOT/releases" 2>/dev/null | tr '\n' ' ')"

# ---------------------------------------------------------------
section "2. 服务状态"
for s in xuancheng-order-mysql xuancheng-order-redis xuancheng-order-backend; do
  st="$(systemctl is-active "$s" 2>/dev/null)"
  [ "$st" = "active" ] && pass "$s 运行中" || fail "$s 状态：${st:-未知}"
done
LISTEN="$(ss -ltn 2>/dev/null | awk '{print $4}' | grep -E ':(13391|16391|18091)$' | tr '\n' ' ')"
info "监听端口：${LISTEN:-（没看到 13391/16391/18091）}"
JAVA_BIN="/www/server/java/jdk-17.0.8/bin/java"
[ -x "$JAVA_BIN" ] && info "Java：$("$JAVA_BIN" -version 2>&1 | head -1)" || warn "找不到 $JAVA_BIN"
SVC_FILE="$(systemctl show -p FragmentPath xuancheng-order-backend 2>/dev/null | cut -d= -f2)"
if [ -n "$SVC_FILE" ] && [ -f "$SVC_FILE" ]; then
  info "后端启动命令：$(grep -E '^ExecStart=' "$SVC_FILE" | head -1 | sed -E 's/(password|secret)=[^ ]+/\1=***/Ig')"
fi

# ---------------------------------------------------------------
section "3. 磁盘与备份"
info "$(df -h "$ROOT" 2>/dev/null | tail -1 | awk '{print "所在磁盘 "$1"，共 "$2"，已用 "$3"，剩余 "$4"（"$5" 已用）"}')"
AVAIL_KB="$(df -Pk "$ROOT" 2>/dev/null | tail -1 | awk '{print $4}')"
if [ -n "$AVAIL_KB" ]; then
  [ "$AVAIL_KB" -gt 5242880 ] && pass "剩余空间大于 5GB" || warn "剩余空间不足 5GB，备份和新版本可能放不下"
fi
info "备份目录：$(du -sh "$ROOT/backups" 2>/dev/null | cut -f1)；最新几份：$(ls -t "$ROOT/backups" 2>/dev/null | head -3 | tr '\n' ' ')"
command -v mysqldump >/dev/null 2>&1 && pass "mysqldump 可用" || fail "找不到 mysqldump，没法做上线前备份"
if crontab -l 2>/dev/null | grep -q backup.sh; then
  info "已有备份计划任务"
else
  info "还没有 backup.sh 的计划任务（正常，上线时安装）"
fi

# ---------------------------------------------------------------
section "4. Nginx 上传上限（需要不小于 55m）"
if [ -f "$NGINX_CONF" ]; then
  LINE="$(grep -E '^\s*client_max_body_size' "$NGINX_CONF" | head -1)"
  if [ -z "$LINE" ]; then
    fail "站点配置里没有 client_max_body_size（默认只有 1m），上线时要加：client_max_body_size 55m;"
  else
    V="$(echo "$LINE" | sed -E 's/.*client_max_body_size\s+([0-9]+)([kKmMgG]?).*/\1 \2/')"
    NUM="${V% *}"; UNIT="${V#* }"
    case "$UNIT" in
      k|K) MB=$((NUM / 1024)) ;;
      g|G) MB=$((NUM * 1024)) ;;
      m|M|"") MB=$NUM ;;
      *) MB=0 ;;
    esac
    if [ "$NUM" = "0" ] || [ "$MB" -ge 55 ]; then pass "$LINE"; else fail "$LINE   <- 小于 55m，上线时改成 55m"; fi
  fi
else
  warn "找不到 $NGINX_CONF，请用 XC_NGINX_CONF 指到实际的站点配置再跑一次"
fi

# ---------------------------------------------------------------
section "5. 生产配置（不打印密码）"
if [ -f "$PROD_YML" ]; then
  FILE_LINES="$(grep -nE '^\s*(max-file-size|max-request-size|profile):' "$PROD_YML")"
  if [ -n "$FILE_LINES" ]; then
    echo "$FILE_LINES" | sed 's/^/         /'
    if echo "$FILE_LINES" | grep -qiE 'max-file-size:\s*10MB'; then
      fail "生产配置把上传上限写死成 10MB，上线时改成 max-file-size: 50MB 和 max-request-size: 55MB，否则提升不生效"
    else
      pass "生产配置没有把上传上限限制在 10MB"
    fi
  else
    pass "生产配置里没有单独写上传上限（沿用新版本自带的 50MB）"
    warn "没找到 profile（上传目录）设置；备份脚本要知道上传目录，上线时用下面第 8 段的结果"
  fi
  if grep -q 'statViewServlet' "$PROD_YML"; then
    info "statViewServlet 配置：$(grep -n -A6 'statViewServlet' "$PROD_YML" | grep -viE 'password|username' | tr -s ' \n' ' ')"
  fi
else
  warn "找不到 $PROD_YML"
fi

# ---------------------------------------------------------------
section "6. 数据库现状"
if [ -f "$MYSQL_CNF" ] && [ -n "$(q 'select 1')" ]; then
  pass "数据库连接正常（$DB）"
  info "订单数：$(q 'select count(*) from jiedan_order')  客户端账号数：$(q "select count(*) from sys_user u join sys_user_role ur on ur.user_id=u.user_id join sys_role r on r.role_id=ur.role_id where r.role_key='customer' and u.del_flag='0'")"
  info "库大小：$(q "select round(sum(data_length+index_length)/1024/1024,1) from information_schema.tables where table_schema='$DB'") MB"

  echo "  -- 拥有管理员/下属角色的账号："
  q "select u.user_id, u.user_name, r.role_key from sys_user u join sys_user_role ur on ur.user_id=u.user_id join sys_role r on r.role_id=ur.role_id where r.role_key in ('admin','employee') and u.del_flag='0' order by u.user_id" | sed 's/^/         /'
  NADMIN="$(q "select count(*) from sys_user u join sys_user_role ur on ur.user_id=u.user_id join sys_role r on r.role_id=ur.role_id where r.role_key='admin' and u.del_flag='0'")"
  [ "$NADMIN" = "1" ] && pass "只有 1 个总管理员账号" || warn "总管理员(admin 角色)账号有 ${NADMIN:-?} 个：它们都会被当成总管理员（都能看全部项目）"
  [ -n "$(q "select 1 from sys_user u join sys_user_role ur on ur.user_id=u.user_id join sys_role r on r.role_id=ur.role_id where r.role_key='employee' limit 1")" ] \
    && info "已存在 employee 角色的账号（是不是已经做过权限升级？）"

  echo "  -- 订单负责人分布（旧版只会有 1 和 2）："
  q "select ifnull(owner_id,'NULL'), count(*) from jiedan_order group by owner_id" | sed 's/^/         /'
  BAD="$(q "select count(*) from jiedan_order where owner_id is not null and owner_id not in (1,2)")"
  U1="$(q "select user_name from sys_user where user_id=1")"
  U2="$(q "select user_name from sys_user where user_id=2")"
  info "用户 id=1：${U1:-（不存在）}   用户 id=2：${U2:-（不存在）}"
  if [ "${BAD:-0}" = "0" ]; then
    pass "负责人只有 1/2（或空）"
  else
    warn "有 $BAD 个订单的负责人不是 1/2，说明可能已经做过权限升级，或数据有出入——先别执行 owner_remap 脚本，把这段输出发给我"
  fi
  [ -n "$U1" ] && pass "id=1 是 $U1（应当是总管理员）" || fail "用户 id=1 不存在"

  echo "  -- 升级状态："
  info "jiedan_account_owner 表：$([ -n "$(q "show tables like 'jiedan_account_owner'")" ] && echo 已存在 || echo 还没有)"
  info "jiedan_order.del_flag 字段：$([ -n "$(q "show columns from jiedan_order like 'del_flag'")" ] && echo 已存在 || echo 还没有)"
  info "jiedan_audit_log 表：$([ -n "$(q "show tables like 'jiedan_audit_log'")" ] && echo 已存在 || echo 还没有)"
  info "owner_remap 已执行标记：$(q "select setting_value from jiedan_setting where setting_key='perm.v1.remapped'" | grep . || echo 否)"

  CAP="$(q "select config_value from sys_config where config_key='sys.account.captchaEnabled'")"
  info "登录验证码开关 captchaEnabled：${CAP:-（未配置）}（false=关；关着时靠“连续输错 5 次锁 10 分钟”防暴力破解）"
  echo "  -- 推送设置（只显示有没有填，不显示内容）："
  q "select setting_key, if(ifnull(setting_value,'')='','(空)','(已设置)') from jiedan_setting where setting_key like 'push.%' order by setting_key" | sed 's/^/         /'

  # 仓库里公开过的演示密码(admin123 / customer123)对应的 BCrypt 密文；密文一模一样 = 密码从没改过
  HASH_ADMIN123='$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2'
  HASH_CUST123='$2a$10$BNYaFwOidOKPaUdXALy8hugrW2cxuB5tYp2J8sE1K.d10fnzUHgsK'
  DEFPW="$(q "select group_concat(user_name) from sys_user where del_flag='0' and password in ('$HASH_ADMIN123','$HASH_CUST123')")"
  if [ -n "$DEFPW" ] && [ "$DEFPW" != "NULL" ]; then
    fail "账号 $DEFPW 的密码还是公开过的演示密码(admin123/customer123)，上线前请先改掉"
  else
    pass "没有账号在用公开过的演示密码"
  fi
  DEMO="$(q "select group_concat(user_name) from sys_user where del_flag='0' and user_name in ('demo','test','admin123')")"
  [ -n "$DEMO" ] && [ "$DEMO" != "NULL" ] && warn "库里有看起来像演示/测试的账号：$DEMO，确认是否需要保留"
else
  fail "连不上数据库（$MYSQL_CNF / $DB），后面的库检查都跳过了"
fi

# ---------------------------------------------------------------
section "7. 附件目录（备份脚本要用）"
for p in "$ROOT/uploads" "$ROOT/data/uploads" "$ROOT/data/upload"; do
  [ -d "$p" ] && info "$p：$(du -sh "$p" 2>/dev/null | cut -f1)，文件数 $(find "$p" -type f 2>/dev/null | wc -l)"
done
M="$(find "$ROOT" -maxdepth 4 -type d -name 'jiedan-media' 2>/dev/null | head -3)"
if [ -n "$M" ]; then
  echo "$M" | while read -r d; do info "找到附件目录 $d（备份时 XC_UPLOADS 填它的上一级：$(dirname "$d")）"; done
else
  warn "在 $ROOT 下没找到 jiedan-media 目录，附件实际放在哪儿要先确认（备份脚本要用）"
fi

# ---------------------------------------------------------------
section "8. 匿名上传漏洞现状（探测，不带文件，不写入任何东西）"
CODE_BODY="$(curl -s -m 8 -X POST "$API/jiedan/media/upload" 2>/dev/null)"
if echo "$CODE_BODY" | grep -q '"code":401'; then
  pass "匿名上传被拒绝（返回 401）——这台已经是修复后的版本"
elif [ -z "$CODE_BODY" ]; then
  warn "探测没有返回内容（后端没起来或地址不对：$API）"
else
  fail "匿名上传没有被拒绝（返回：$(echo "$CODE_BODY" | cut -c1-120)）——漏洞仍在，升级后再测应当是 401"
fi

# ---------------------------------------------------------------
echo
echo "== 汇总：通过 $PASS，注意 $WARN，失败 $FAIL =="
echo "（[失败] 里“匿名上传没有被拒绝”“Nginx 上限”“上传上限写死 10MB”是上线时正好要处理的，不用先解决；其余失败项请先处理）"
