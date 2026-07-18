-- ============================================================
-- 玄程 AI 改码流水线 V1.4（控制面，兼容 V1.3 原地升级）
-- 适用：MySQL 5.7+；脚本可重复执行，不创建默认账号和默认密码。
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
  order_id            bigint       NULL COMMENT '绑定的接单项目ID；客户Bug据此自动入队',
  automation_mode     varchar(20)  NOT NULL DEFAULT 'manual' COMMENT 'manual/auto_pr/auto_deploy',
  deploy_workflow     varchar(120) NOT NULL DEFAULT 'deploy.yml' COMMENT 'GitHub Actions部署工作流文件名',
  deploy_timeout_min  int          NOT NULL DEFAULT 20 COMMENT '等待部署完成分钟数',
  production_url      varchar(1000) NOT NULL DEFAULT '' COMMENT '交付给客户的线上地址',
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
  UNIQUE KEY uk_ai_project_order (order_id),
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
  source_type          varchar(30)   NOT NULL DEFAULT 'manual' COMMENT 'manual/customer_bug/customer_bug_update',
  source_id            bigint        NULL COMMENT '来源记录ID，用于自动入队幂等',
  status               varchar(30)   NOT NULL DEFAULT 'draft' COMMENT '任务状态机',
  current_attempt_id   bigint        NULL COMMENT '当前执行尝试',
  approved_attempt_id  bigint        NULL COMMENT '批准的执行尝试',
  approved_head_sha    varchar(64)   NULL COMMENT '批准时Git head指纹',
  approved_diff_sha    varchar(64)   NULL COMMENT '批准时diff指纹',
  delivery_status      varchar(30)   NOT NULL DEFAULT 'pending' COMMENT 'pending/delivered/failed',
  merged_sha           varchar(64)   NULL COMMENT '自动合并后的提交SHA',
  deployment_url       varchar(1000) NULL COMMENT 'GitHub Actions运行地址',
  delivery_url         varchar(1000) NULL COMMENT '客户验收地址',
  delivery_error       text          NULL COMMENT '自动交付失败原因',
  delivered_at         datetime      NULL,
  version              int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  created_by           varchar(64)   NOT NULL DEFAULT '',
  create_time          datetime      NOT NULL,
  updated_by           varchar(64)   NOT NULL DEFAULT '',
  update_time          datetime      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_task_queue (status, project_id, id),
  KEY idx_ai_task_order (order_id, id),
  KEY idx_ai_task_attempt (current_attempt_id),
  UNIQUE KEY uk_ai_task_source (source_type, source_id)
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

-- V1.4：存量 V1.3 数据库幂等升级。MySQL 5.7/8 均可执行。
SET @db = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_project' AND column_name='order_id')=0,
  'ALTER TABLE ai_project ADD COLUMN order_id BIGINT NULL COMMENT ''绑定的接单项目ID'' AFTER profile_key', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_project' AND column_name='automation_mode')=0,
  'ALTER TABLE ai_project ADD COLUMN automation_mode VARCHAR(20) NOT NULL DEFAULT ''manual'' AFTER order_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_project' AND column_name='deploy_workflow')=0,
  'ALTER TABLE ai_project ADD COLUMN deploy_workflow VARCHAR(120) NOT NULL DEFAULT ''deploy.yml'' AFTER automation_mode', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_project' AND column_name='deploy_timeout_min')=0,
  'ALTER TABLE ai_project ADD COLUMN deploy_timeout_min INT NOT NULL DEFAULT 20 AFTER deploy_workflow', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_project' AND column_name='production_url')=0,
  'ALTER TABLE ai_project ADD COLUMN production_url VARCHAR(1000) NOT NULL DEFAULT '''' AFTER deploy_timeout_min', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@db AND table_name='ai_project' AND index_name='uk_ai_project_order')=0,
  'CREATE UNIQUE INDEX uk_ai_project_order ON ai_project(order_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='source_type')=0,
  'ALTER TABLE ai_task ADD COLUMN source_type VARCHAR(30) NOT NULL DEFAULT ''manual'' AFTER risk_level', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='source_id')=0,
  'ALTER TABLE ai_task ADD COLUMN source_id BIGINT NULL AFTER source_type', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='delivery_status')=0,
  'ALTER TABLE ai_task ADD COLUMN delivery_status VARCHAR(30) NOT NULL DEFAULT ''pending'' AFTER approved_diff_sha', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='merged_sha')=0,
  'ALTER TABLE ai_task ADD COLUMN merged_sha VARCHAR(64) NULL AFTER delivery_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='deployment_url')=0,
  'ALTER TABLE ai_task ADD COLUMN deployment_url VARCHAR(1000) NULL AFTER merged_sha', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='delivery_url')=0,
  'ALTER TABLE ai_task ADD COLUMN delivery_url VARCHAR(1000) NULL AFTER deployment_url', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='delivery_error')=0,
  'ALTER TABLE ai_task ADD COLUMN delivery_error TEXT NULL AFTER delivery_url', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_task' AND column_name='delivered_at')=0,
  'ALTER TABLE ai_task ADD COLUMN delivered_at DATETIME NULL AFTER delivery_error', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@db AND table_name='ai_task' AND index_name='uk_ai_task_source')=0,
  'CREATE UNIQUE INDEX uk_ai_task_source ON ai_task(source_type, source_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 上线后核对：
-- SELECT role_id, role_name, role_key FROM sys_role WHERE role_key='ai_worker';
-- 请通过用户管理创建独立 aiworker 账号并分配该角色；不要把管理员账号写入 Worker 配置。
