#!/usr/bin/env bash
# 玄成接单系统 · 自动备份脚本
#
# 做三件事：
#   1) 数据库：mysqldump 压缩成 db_日期_时分.sql.gz，并检查文件是否完整、是否真的包含订单表；
#   2) 附件：上传的图片/视频/语音文件名都是随机且不会被修改，所以只需“增量镜像”——
#      只复制新增的文件，且镜像里的文件不随原文件删除而删除（误删也找得回来）；
#   3) 清理：日备份留 KEEP_DAYS 天；每月 1 号的那份留 KEEP_MONTHLY_DAYS 天。
#
# 部署：放到服务器 /opt/xuancheng-order/bin/backup.sh，chmod +x，然后加一条每天凌晨的计划任务（宝塔「计划任务」或 crontab）：
#   30 3 * * * /opt/xuancheng-order/bin/backup.sh >> /opt/xuancheng-order/logs/backup.log 2>&1
# 每周再加一条“恢复演练”（把备份真的导入一个临时库再删掉，证明备份能用）：
#   0 4 * * 0 VERIFY_RESTORE=1 /opt/xuancheng-order/bin/backup.sh >> /opt/xuancheng-order/logs/backup.log 2>&1
#
# 重要：备份和线上放在同一块磁盘上，只能防“误操作”，防不了“磁盘坏了”。
# 请设置 OFFSITE_DIR 指向另一块盘 / 挂载的网盘 / 另一台机器的挂载目录，脚本会把新备份同步过去。
#
# 所有路径都可以用环境变量覆盖；默认值就是 deploy/production.md 里的线上目录。
set -uo pipefail
umask 077

ROOT="${XC_ROOT:-/opt/xuancheng-order}"
DB_NAME="${XC_DB:-xc_jiedan}"
MYSQL_DEFAULTS="${XC_MYSQL_DEFAULTS:-$ROOT/config/mysql-root.cnf}"
UPLOADS_DIR="${XC_UPLOADS:-$ROOT/uploads}"
BACKUP_DIR="${XC_BACKUPS:-$ROOT/backups}"
OFFSITE_DIR="${OFFSITE_DIR:-}"
KEEP_DAYS="${KEEP_DAYS:-30}"
KEEP_MONTHLY_DAYS="${KEEP_MONTHLY_DAYS:-365}"
VERIFY_RESTORE="${VERIFY_RESTORE:-0}"
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-mysqldump}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

STAMP="$(date +%F_%H%M)"
TODAY="$(date +%F)"

log() { echo "[$(date '+%F %T')] $*"; }
fail() { log "失败：$*"; exit 1; }

[ -f "$MYSQL_DEFAULTS" ] || fail "找不到数据库凭据文件 $MYSQL_DEFAULTS（可用 XC_MYSQL_DEFAULTS 指定）"
mkdir -p "$BACKUP_DIR" || fail "无法创建备份目录 $BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

# 同一时间只跑一份，避免上一次还没跑完又开始下一次
if command -v flock >/dev/null 2>&1; then
  exec 9>"$BACKUP_DIR/.backup.lock"
  flock -n 9 || { log "已有备份在运行，本次跳过"; exit 0; }
fi

# ---------- 1. 数据库 ----------
DUMP="$BACKUP_DIR/db_${STAMP}.sql.gz"
TMP_DUMP="$DUMP.partial"
log "开始备份数据库 $DB_NAME -> $DUMP"
if ! "$MYSQLDUMP_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" --single-transaction --routines \
      --default-character-set=utf8mb4 "$DB_NAME" | gzip -c > "$TMP_DUMP"; then
  rm -f "$TMP_DUMP"
  fail "mysqldump 执行失败"
fi
gzip -t "$TMP_DUMP" || { rm -f "$TMP_DUMP"; fail "备份文件损坏（gzip 校验失败）"; }
SIZE="$(wc -c < "$TMP_DUMP" | tr -d ' ')"
[ "$SIZE" -gt 2048 ] || { rm -f "$TMP_DUMP"; fail "备份文件异常小（${SIZE} 字节），已丢弃"; }
gzip -dc "$TMP_DUMP" | grep -q 'CREATE TABLE `jiedan_order`' || { rm -f "$TMP_DUMP"; fail "备份里没有订单表，已丢弃"; }
mv "$TMP_DUMP" "$DUMP"
log "数据库备份完成：$(basename "$DUMP")（${SIZE} 字节）"

# ---------- 2. 附件（增量镜像） ----------
MIRROR="$BACKUP_DIR/uploads-mirror"
if [ -d "$UPLOADS_DIR" ]; then
  mkdir -p "$MIRROR"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --ignore-existing "$UPLOADS_DIR"/ "$MIRROR"/ || log "警告：附件镜像出错（rsync）"
  else
    cp -a -n "$UPLOADS_DIR"/. "$MIRROR"/ || log "警告：附件镜像出错（cp）"
  fi
  log "附件镜像完成：$(find "$MIRROR" -type f 2>/dev/null | wc -l | tr -d ' ') 个文件"
else
  log "警告：没有找到附件目录 $UPLOADS_DIR（可用 XC_UPLOADS 指定），已跳过附件备份"
fi

# ---------- 3. 异地 / 另一块盘 ----------
if [ -n "$OFFSITE_DIR" ]; then
  if mkdir -p "$OFFSITE_DIR" 2>/dev/null; then
    cp -f "$DUMP" "$OFFSITE_DIR"/ && log "已同步数据库备份到 $OFFSITE_DIR" || log "警告：同步数据库备份到 $OFFSITE_DIR 失败"
    if [ -d "$MIRROR" ]; then
      if command -v rsync >/dev/null 2>&1; then
        rsync -a --ignore-existing "$MIRROR"/ "$OFFSITE_DIR/uploads-mirror"/ || log "警告：同步附件到 $OFFSITE_DIR 失败"
      else
        mkdir -p "$OFFSITE_DIR/uploads-mirror" && cp -a -n "$MIRROR"/. "$OFFSITE_DIR/uploads-mirror"/ || log "警告：同步附件到 $OFFSITE_DIR 失败"
      fi
    fi
  else
    log "警告：无法访问 OFFSITE_DIR=$OFFSITE_DIR，本次没有异地备份"
  fi
else
  log "提示：没有设置 OFFSITE_DIR，备份只在本机磁盘上"
fi

# ---------- 4. 恢复演练（可选） ----------
if [ "$VERIFY_RESTORE" = "1" ]; then
  VERIFY_DB="xc_backup_verify_tmp"
  [ "$VERIFY_DB" != "$DB_NAME" ] || fail "演练库名不能和线上库同名"
  log "恢复演练：把 $(basename "$DUMP") 导入临时库 $VERIFY_DB"
  "$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" -e "drop database if exists \`$VERIFY_DB\`; create database \`$VERIFY_DB\` default character set utf8mb4" \
    || fail "无法创建演练用临时库"
  if gzip -dc "$DUMP" | "$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" --default-character-set=utf8mb4 "$VERIFY_DB"; then
    LIVE="$("$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" -N -e "select count(*) from \`$DB_NAME\`.jiedan_order" 2>/dev/null)"
    COPY="$("$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" -N -e "select count(*) from \`$VERIFY_DB\`.jiedan_order" 2>/dev/null)"
    log "恢复演练：线上订单 ${LIVE:-?} 条，备份里订单 ${COPY:-?} 条（备份之后新增的订单不在其中，略少是正常的）"
    [ -n "$COPY" ] || { "$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" -e "drop database if exists \`$VERIFY_DB\`"; fail "恢复演练失败：读不到备份里的订单表"; }
  else
    "$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" -e "drop database if exists \`$VERIFY_DB\`"
    fail "恢复演练失败：备份无法导入"
  fi
  "$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" -e "drop database if exists \`$VERIFY_DB\`"
  log "恢复演练通过，临时库已删除"
fi

# ---------- 5. 清理旧备份 ----------
# 每月 1 号的备份文件名形如 db_2026-12-01_0330.sql.gz，保留更久；其余按 KEEP_DAYS 清理
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'db_*.sql.gz' -mtime +"$KEEP_DAYS" ! -name 'db_*-01_*.sql.gz' -print -delete \
  | sed 's/^/已清理旧备份：/' | while read -r line; do log "$line"; done
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'db_*-01_*.sql.gz' -mtime +"$KEEP_MONTHLY_DAYS" -print -delete \
  | sed 's/^/已清理旧月备份：/' | while read -r line; do log "$line"; done

log "备份结束（$TODAY）。当前保留 $(ls "$BACKUP_DIR"/db_*.sql.gz 2>/dev/null | wc -l | tr -d ' ') 份数据库备份"
exit 0
