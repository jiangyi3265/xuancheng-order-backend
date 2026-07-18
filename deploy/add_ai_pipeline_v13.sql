-- ============================================================
-- 玄程 AI 改码流水线 V1.3（控制面）
-- 适用：MySQL 8.x；脚本可重复执行，不创建默认账号和默认密码。
-- AI Worker 用户请在若依用户管理中新建，再分配 role_key=ai_worker 的角色。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_project (
  id                  bigint       NOT NULL AUTO_INCREMENT COMMENT '项目ID',
  name                varchar(80)  NOT NULL COMMENT '项目名称',
  repo_url            varchar(500) NOT NULL COMMENT '不含凭证的GitHub仓库地址',
  default_branch      varchar(100) NOT NULL DEFAULT 'main' COMMENT '基线分支',
  provider            varchar(20)  NOT NULL DEFAULT 'codex' COMMENT 'codex/claude/hermes/custom',
  model               varchar(100) NOT NULL DEFAULT '' COMMENT '模型名，空值表示Worker默认',
  profile_key         varchar(100) NOT NULL DEFAULT 'default' COMMENT 'Worker本地执行配置键',
  validation_commands text         NOT NULL COMMENT '验证命令JSON数组',
  forbidden_paths     text         NOT NULL COMMENT '禁止改动路径glob JSON数组',
  max_parallel        int          NOT NULL DEFAULT 1 COMMENT '单项目最大并发，1-4',
  enabled             tinyint(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  version             int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  created_by          varchar(64)  NOT NULL DEFAULT '',
  create_time         datetime     NOT NULL,
  updated_by          varchar(64)  NOT NULL DEFAULT '',
  update_time         datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_project_repo (repo_url),
  KEY idx_ai_project_enabled (enabled, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI改码项目配置';

CREATE TABLE IF NOT EXISTS ai_task (
  id                   bigint        NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  project_id           bigint        NOT NULL COMMENT 'AI项目ID',
  order_id             bigint        NULL COMMENT '关联接单订单ID',
  bug_id               bigint        NULL COMMENT '关联Bug ID',
  title                varchar(120)  NOT NULL COMMENT '任务标题',
  prompt               longtext      NOT NULL COMMENT '完整改码说明，仅认领后下发',
  prompt_hash          char(64)      NOT NULL COMMENT '规范化提示词SHA-256',
  prompt_version       int           NOT NULL DEFAULT 1,
  risk_level           varchar(20)   NOT NULL DEFAULT 'low' COMMENT 'low/medium/high',
  status               varchar(30)   NOT NULL DEFAULT 'draft' COMMENT '任务状态机',
  current_attempt_id   bigint        NULL COMMENT '当前执行尝试',
  approved_attempt_id  bigint        NULL COMMENT '批准的执行尝试',
  approved_head_sha    varchar(64)   NULL COMMENT '批准时Git head指纹',
  approved_diff_sha    varchar(64)   NULL COMMENT '批准时diff指纹',
  version              int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  created_by           varchar(64)   NOT NULL DEFAULT '',
  create_time          datetime      NOT NULL,
  updated_by           varchar(64)   NOT NULL DEFAULT '',
  update_time          datetime      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_task_queue (status, project_id, id),
  KEY idx_ai_task_order (order_id, id),
  KEY idx_ai_task_attempt (current_attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI改码业务任务';

CREATE TABLE IF NOT EXISTS ai_attempt (
  id                   bigint        NOT NULL AUTO_INCREMENT COMMENT '执行尝试ID',
  task_id              bigint        NOT NULL,
  attempt_no           int           NOT NULL,
  request_id           varchar(64)   NOT NULL COMMENT '认领幂等键',
  fence_token          varchar(64)   NOT NULL COMMENT '租约围栏令牌',
  worker_id            varchar(80)   NOT NULL,
  provider             varchar(20)   NOT NULL,
  model                varchar(100)  NOT NULL DEFAULT '',
  status               varchar(30)   NOT NULL COMMENT 'claimed/running/succeeded/failed/expired',
  lease_expire_time    datetime      NOT NULL,
  branch_name          varchar(200)  NOT NULL COMMENT '不可复用的attempt分支',
  base_sha             varchar(64)   NOT NULL,
  head_sha             varchar(64)   NULL,
  tree_sha             varchar(64)   NULL,
  diff_sha             varchar(64)   NULL,
  pr_url               varchar(1000) NULL,
  pr_number            bigint        NULL,
  policy_passed        tinyint(1)    NULL,
  validation_passed    tinyint(1)    NULL,
  validation_summary   text          NULL,
  error_code           varchar(80)   NULL,
  error_message        text          NULL,
  started_at           datetime      NULL,
  finished_at          datetime      NULL,
  create_time          datetime      NOT NULL,
  update_time          datetime      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_attempt_task_no (task_id, attempt_no),
  UNIQUE KEY uk_ai_attempt_request (request_id),
  KEY idx_ai_attempt_lease (status, lease_expire_time),
  KEY idx_ai_attempt_worker (worker_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI改码执行尝试';

CREATE TABLE IF NOT EXISTS ai_attempt_log (
  id           bigint       NOT NULL AUTO_INCREMENT,
  attempt_id   bigint       NOT NULL,
  seq          bigint       NOT NULL COMMENT 'Worker内单调递增序号',
  level        varchar(10)  NOT NULL DEFAULT 'info',
  message      varchar(4000) NOT NULL,
  create_time  datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_attempt_log_seq (attempt_id, seq),
  KEY idx_ai_attempt_log_time (attempt_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI执行流式日志';

CREATE TABLE IF NOT EXISTS ai_event (
  id           bigint       NOT NULL AUTO_INCREMENT,
  task_id      bigint       NOT NULL,
  attempt_id   bigint       NULL,
  event_type   varchar(80)  NOT NULL,
  actor_type   varchar(20)  NOT NULL COMMENT 'user/worker/system',
  actor_id     varchar(80)  NOT NULL,
  event_data   text         NULL COMMENT '事件JSON快照',
  create_time  datetime     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_event_task (task_id, id),
  KEY idx_ai_event_attempt (attempt_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI改码审计事件';

INSERT INTO sys_role
  (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly,
   status, del_flag, create_by, create_time, remark)
SELECT 'AI 执行器', 'ai_worker', 90, '1', 0, 0, '0', '0', 'admin', NOW(),
       '只允许访问/jiedan/ai/worker接口；禁止登录管理端'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key = 'ai_worker');

-- 上线后核对：
-- SELECT role_id, role_name, role_key FROM sys_role WHERE role_key='ai_worker';
-- 请通过用户管理创建独立 aiworker 账号并分配该角色；不要把管理员账号写入 Worker 配置。
