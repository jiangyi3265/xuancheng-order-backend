-- MySQL dump 10.13  Distrib 8.0.45, for Win64 (x86_64)
--
-- Host: localhost    Database: xc_jiedan
-- ------------------------------------------------------
-- Server version	8.0.45

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `jiedan_order`
--

DROP TABLE IF EXISTS `jiedan_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `jiedan_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no` varchar(32) DEFAULT '' COMMENT '订单编号',
  `title` varchar(255) NOT NULL COMMENT '项目名',
  `channel` varchar(20) DEFAULT 'wechat' COMMENT '来源渠道',
  `customer` varchar(100) DEFAULT '' COMMENT '客户',
  `customer_account` varchar(64) DEFAULT '' COMMENT '归属客户账号(sys_user.user_name)',
  `contact` varchar(100) DEFAULT '' COMMENT '联系方式',
  `amount` decimal(12,2) DEFAULT '0.00' COMMENT '报价',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人(成员id)',
  `status` varchar(20) DEFAULT 'pending' COMMENT '状态',
  `priority` varchar(20) DEFAULT 'medium' COMMENT '优先级',
  `deadline` varchar(20) DEFAULT '' COMMENT '截止日期',
  `requirement` text COMMENT '需求描述',
  `attachments` longtext COMMENT '需求材料(JSON)',
  `notes` longtext COMMENT '项目记事本正文(内部)',
  `note_attachments` longtext COMMENT '记事本附件(JSON,内部)',
  `unread` varchar(50) DEFAULT '' COMMENT '未读成员(逗号分隔)',
  `paid` tinyint(1) DEFAULT '0' COMMENT '是否收款',
  `received_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '已收金额(收款流水合计)',
  `assign_state` varchar(10) NOT NULL DEFAULT '' COMMENT '接收状态：空=无需接收/已接收 pending=等负责人接收',
  `revisions` int DEFAULT '0' COMMENT '改稿次数',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '0正常 1已删除(回收站)',
  `delete_time` datetime DEFAULT NULL COMMENT '删除时间',
  `delete_by` varchar(64) DEFAULT NULL COMMENT '删除人',
  `delete_by_id` bigint DEFAULT NULL COMMENT '删除人账号id(客户删除为空)',
  `next_step` varchar(200) DEFAULT NULL COMMENT '下一步：做什么（内部）',
  `next_owner_id` bigint DEFAULT NULL COMMENT '下一步：谁来做（成员id）',
  `next_due` varchar(20) DEFAULT NULL COMMENT '下一步：截止日期 yyyy-MM-dd',
  `handled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '内部手动标记：0未处理 1已处理',
  `urgent_until` datetime DEFAULT NULL COMMENT '项目加急截止时间（北京时间）',
  `handled_time` datetime DEFAULT NULL COMMENT '最近一次实际切换处理状态的时间',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_order_customer_account_id` (`customer_account`,`id`),
  KEY `idx_jiedan_order_del_owner` (`del_flag`,`owner_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接单订单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `jiedan_order`
--

LOCK TABLES `jiedan_order` WRITE;
/*!40000 ALTER TABLE `jiedan_order` DISABLE KEYS */;

/*!40000 ALTER TABLE `jiedan_order` ENABLE KEYS */;
UNLOCK TABLES;
ALTER TABLE `jiedan_order` ADD COLUMN `version` bigint NOT NULL DEFAULT '0' COMMENT '数据版本' AFTER `revisions`;

--
-- Table structure for table `jiedan_bug`
--

DROP TABLE IF EXISTS `jiedan_bug`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `jiedan_bug` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Bug ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `content` text COMMENT 'Bug说明',
  `attachments` longtext COMMENT '附件(JSON)',
  `status` varchar(20) COLLATE utf8mb4_general_ci DEFAULT 'open' COMMENT '状态 open/resolved',
  `created_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_bug_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='项目Bug清单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `jiedan_bug`
--

LOCK TABLES `jiedan_bug` WRITE;
/*!40000 ALTER TABLE `jiedan_bug` DISABLE KEYS */;
/*!40000 ALTER TABLE `jiedan_bug` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `jiedan_bug_update`
--

DROP TABLE IF EXISTS `jiedan_bug_update`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `jiedan_bug_update` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '追加ID',
  `bug_id` bigint NOT NULL COMMENT 'Bug ID',
  `content` text COMMENT '追加说明',
  `attachments` longtext COMMENT '附件(JSON)',
  `created_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_bug_update_bug_id` (`bug_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Bug追加QA';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `jiedan_bug_update`
--

LOCK TABLES `jiedan_bug_update` WRITE;
/*!40000 ALTER TABLE `jiedan_bug_update` DISABLE KEYS */;
/*!40000 ALTER TABLE `jiedan_bug_update` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `jiedan_setting`
--

DROP TABLE IF EXISTS `jiedan_setting`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `jiedan_setting` (
  `setting_key` varchar(64) NOT NULL COMMENT '配置键',
  `setting_value` varchar(500) DEFAULT '' COMMENT '配置值',
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接单系统设置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `jiedan_setting`
--

LOCK TABLES `jiedan_setting` WRITE;
/*!40000 ALTER TABLE `jiedan_setting` DISABLE KEYS */;
INSERT INTO `jiedan_setting` VALUES ('push.aheadDays','1'),('push.enabled','true'),('push.sendkey.1',''),('push.sendkey.2','');
/*!40000 ALTER TABLE `jiedan_setting` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `jiedan_timeline`
--

DROP TABLE IF EXISTS `jiedan_timeline`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `jiedan_timeline` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `user_name` varchar(64) DEFAULT '' COMMENT '记录人',
  `type` varchar(20) DEFAULT 'note' COMMENT '类型 create/status/note',
  `content` varchar(1000) DEFAULT '' COMMENT '内容',
  `attachments` longtext COMMENT '附件(JSON)',
  `create_time` datetime DEFAULT NULL COMMENT '时间',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_jiedan_timeline_order_type_id` (`order_id`,`type`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接单进度时间线';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `jiedan_timeline`
--

LOCK TABLES `jiedan_timeline` WRITE;
/*!40000 ALTER TABLE `jiedan_timeline` DISABLE KEYS */;

/*!40000 ALTER TABLE `jiedan_timeline` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `jiedan_account_owner`
-- 账号归属：下属管理员 -> 上级，客户 -> 负责管理员
--

DROP TABLE IF EXISTS `jiedan_account_owner`;
CREATE TABLE `jiedan_account_owner` (
  `user_id` bigint NOT NULL COMMENT '账号(sys_user.user_id)：下属管理员或客户',
  `owner_user_id` bigint NOT NULL COMMENT '归属/上级管理员(sys_user.user_id)',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`user_id`),
  KEY `idx_jiedan_account_owner_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='账号归属（下属->上级、客户->负责管理员）';

--
-- Table structure for table `jiedan_payment`
--

DROP TABLE IF EXISTS `jiedan_payment`;
CREATE TABLE `jiedan_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '收款ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `amount` decimal(12,2) NOT NULL COMMENT '本次收款金额',
  `pay_date` varchar(10) NOT NULL COMMENT '收款日期 yyyy-MM-dd',
  `note` varchar(200) DEFAULT '' COMMENT '备注，如 定金/尾款/微信转账',
  `created_by` varchar(64) DEFAULT '' COMMENT '记录人',
  `create_time` datetime NOT NULL COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_payment_order` (`order_id`),
  KEY `idx_jiedan_payment_date` (`pay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收款流水';

--
-- Table structure for table `jiedan_approval`
--

DROP TABLE IF EXISTS `jiedan_approval`;
CREATE TABLE `jiedan_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '申请ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `type` varchar(16) NOT NULL COMMENT '申请类型 amount改报价 cancel取消 delete删除 payment收款',
  `payload` varchar(1000) DEFAULT '' COMMENT '申请内容(JSON)，如 {"amount":"4500"}',
  `reason` varchar(500) DEFAULT '' COMMENT '申请原因',
  `status` varchar(12) NOT NULL DEFAULT 'pending' COMMENT 'pending待批 approved已批准 rejected已驳回 withdrawn已撤回',
  `requester_id` bigint NOT NULL COMMENT '申请人账号id',
  `requester_name` varchar(64) DEFAULT '' COMMENT '申请人姓名',
  `create_time` datetime NOT NULL COMMENT '申请时间',
  `handler_id` bigint DEFAULT NULL COMMENT '处理人账号id',
  `handler_name` varchar(64) DEFAULT '' COMMENT '处理人姓名',
  `handle_note` varchar(500) DEFAULT '' COMMENT '处理说明（驳回原因等）',
  `handle_time` datetime DEFAULT NULL COMMENT '处理时间',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_approval_status` (`status`,`id`),
  KEY `idx_jiedan_approval_order` (`order_id`,`status`),
  KEY `idx_jiedan_approval_requester` (`requester_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='员工申请（需老板批准）';

--
-- Table structure for table `jiedan_audit_log`
-- 操作日志：谁在什么时候做了什么重要操作
--

DROP TABLE IF EXISTS `jiedan_audit_log`;
CREATE TABLE `jiedan_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `create_time` datetime NOT NULL COMMENT '操作时间',
  `actor_id` bigint DEFAULT NULL COMMENT '操作人账号id(系统任务为空)',
  `actor_name` varchar(64) DEFAULT '' COMMENT '操作人姓名',
  `actor_role` varchar(16) DEFAULT '' COMMENT 'boss/employee/customer/system',
  `action` varchar(32) NOT NULL COMMENT '操作类型，如 order.delete',
  `target_type` varchar(16) DEFAULT '' COMMENT '对象类型 order/bug/staff/customer/account',
  `target_id` bigint DEFAULT NULL COMMENT '对象id',
  `target_name` varchar(200) DEFAULT '' COMMENT '对象名称(项目名/账号名)',
  `detail` varchar(1000) DEFAULT '' COMMENT '详情，如 金额 1000 -> 1500',
  `ip` varchar(64) DEFAULT '' COMMENT '操作人IP',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_audit_time` (`create_time`),
  KEY `idx_jiedan_audit_actor` (`actor_id`,`create_time`),
  KEY `idx_jiedan_audit_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志';

--
-- Table structure for table `sys_config`
--

DROP TABLE IF EXISTS `sys_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_config` (
  `config_id` int NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `config_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键名',
  `config_value` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键值',
  `config_type` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`config_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='参数配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_config`
--

LOCK TABLES `sys_config` WRITE;
/*!40000 ALTER TABLE `sys_config` DISABLE KEYS */;
INSERT INTO `sys_config` VALUES (1,'主框架页-默认皮肤样式名称','sys.index.skinName','skin-blue','Y','admin','2026-06-20 00:43:42','',NULL,''),(2,'用户管理-账号初始密码','sys.user.initPassword','123456','Y','admin','2026-06-20 00:43:42','',NULL,''),(3,'主框架页-侧边栏主题','sys.index.sideTheme','theme-dark','Y','admin','2026-06-20 00:43:42','',NULL,''),(4,'账号自助-验证码开关','sys.account.captchaEnabled','false','Y','admin','2026-06-20 00:43:42','',NULL,''),(5,'账号自助-是否开启用户注册功能','sys.account.registerUser','false','Y','admin','2026-06-20 00:43:42','',NULL,'');
/*!40000 ALTER TABLE `sys_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_dept`
--

DROP TABLE IF EXISTS `sys_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dept` (
  `dept_id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门id',
  `parent_id` bigint DEFAULT '0' COMMENT '父部门id',
  `ancestors` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `leader` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`)
) ENGINE=InnoDB AUTO_INCREMENT=200 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='部门表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_dept`
--

LOCK TABLES `sys_dept` WRITE;
/*!40000 ALTER TABLE `sys_dept` DISABLE KEYS */;
INSERT INTO `sys_dept` VALUES (100,0,'0','总公司',0,'admin','15888888888','admin@qq.com','0','0','admin','2026-06-20 00:43:42','',NULL),(101,100,'0,100','默认部门',1,'admin','15888888888','admin@qq.com','0','0','admin','2026-06-20 00:43:42','',NULL);
/*!40000 ALTER TABLE `sys_dept` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_dict_data`
--

DROP TABLE IF EXISTS `sys_dict_data`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_data` (
  `dict_code` bigint NOT NULL AUTO_INCREMENT COMMENT '字典编码',
  `dict_sort` int DEFAULT '0' COMMENT '字典排序',
  `dict_label` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典标签',
  `dict_value` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `css_class` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
  `list_class` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '表格回显样式',
  `is_default` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_code`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典数据表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_dict_data`
--

LOCK TABLES `sys_dict_data` WRITE;
/*!40000 ALTER TABLE `sys_dict_data` DISABLE KEYS */;
INSERT INTO `sys_dict_data` VALUES (1,1,'男','0','sys_user_sex','','','Y','0','admin','2026-06-20 00:43:42','',NULL,''),(2,2,'女','1','sys_user_sex','','','N','0','admin','2026-06-20 00:43:42','',NULL,''),(3,3,'未知','2','sys_user_sex','','','N','0','admin','2026-06-20 00:43:42','',NULL,''),(4,1,'显示','0','sys_show_hide','','primary','Y','0','admin','2026-06-20 00:43:42','',NULL,''),(5,2,'隐藏','1','sys_show_hide','','danger','N','0','admin','2026-06-20 00:43:42','',NULL,''),(6,1,'正常','0','sys_normal_disable','','primary','Y','0','admin','2026-06-20 00:43:42','',NULL,''),(7,2,'停用','1','sys_normal_disable','','danger','N','0','admin','2026-06-20 00:43:42','',NULL,''),(12,1,'是','Y','sys_yes_no','','primary','Y','0','admin','2026-06-20 00:43:42','',NULL,''),(13,2,'否','N','sys_yes_no','','danger','N','0','admin','2026-06-20 00:43:42','',NULL,''),(28,1,'成功','0','sys_common_status','','primary','N','0','admin','2026-06-20 00:43:42','',NULL,''),(29,2,'失败','1','sys_common_status','','danger','N','0','admin','2026-06-20 00:43:42','',NULL,'');
/*!40000 ALTER TABLE `sys_dict_data` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_dict_type`
--

DROP TABLE IF EXISTS `sys_dict_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_type` (
  `dict_id` bigint NOT NULL AUTO_INCREMENT COMMENT '字典主键',
  `dict_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典名称',
  `dict_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_id`),
  UNIQUE KEY `dict_type` (`dict_type`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典类型表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_dict_type`
--

LOCK TABLES `sys_dict_type` WRITE;
/*!40000 ALTER TABLE `sys_dict_type` DISABLE KEYS */;
INSERT INTO `sys_dict_type` VALUES (1,'用户性别','sys_user_sex','0','admin','2026-06-20 00:43:42','',NULL,''),(2,'菜单状态','sys_show_hide','0','admin','2026-06-20 00:43:42','',NULL,''),(3,'系统开关','sys_normal_disable','0','admin','2026-06-20 00:43:42','',NULL,''),(6,'系统是否','sys_yes_no','0','admin','2026-06-20 00:43:42','',NULL,''),(10,'系统状态','sys_common_status','0','admin','2026-06-20 00:43:42','',NULL,'');
/*!40000 ALTER TABLE `sys_dict_type` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_logininfor`
--

DROP TABLE IF EXISTS `sys_logininfor`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_logininfor` (
  `info_id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
  `user_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户账号',
  `ipaddr` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录IP地址',
  `login_location` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录地点',
  `browser` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作系统',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '登录状态（0成功 1失败）',
  `msg` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '提示消息',
  `login_time` datetime DEFAULT NULL COMMENT '访问时间',
  PRIMARY KEY (`info_id`),
  KEY `idx_sys_logininfor_s` (`status`),
  KEY `idx_sys_logininfor_lt` (`login_time`)
) ENGINE=InnoDB AUTO_INCREMENT=106 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统访问记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_logininfor`
--

LOCK TABLES `sys_logininfor` WRITE;
/*!40000 ALTER TABLE `sys_logininfor` DISABLE KEYS */;

/*!40000 ALTER TABLE `sys_logininfor` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_menu`
--

DROP TABLE IF EXISTS `sys_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `menu_id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件路径',
  `query` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '路由参数',
  `route_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由名称',
  `is_frame` int DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  `is_cache` int DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）',
  `menu_type` char(1) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '#' COMMENT '菜单图标',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`menu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜单权限表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_menu`
--

LOCK TABLES `sys_menu` WRITE;
/*!40000 ALTER TABLE `sys_menu` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_notice`
--

DROP TABLE IF EXISTS `sys_notice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_notice` (
  `notice_id` int NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `notice_title` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告标题',
  `notice_type` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告类型（1通知 2公告）',
  `notice_content` longblob COMMENT '公告内容',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`notice_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通知公告表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_notice`
--

LOCK TABLES `sys_notice` WRITE;
/*!40000 ALTER TABLE `sys_notice` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_notice` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_oper_log`
--

DROP TABLE IF EXISTS `sys_oper_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_oper_log` (
  `oper_id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `title` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '模块标题',
  `business_type` int DEFAULT '0' COMMENT '业务类型（0其它 1新增 2修改 3删除）',
  `method` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求方式',
  `operator_type` int DEFAULT '0' COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
  `oper_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作人员',
  `dept_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `oper_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '主机地址',
  `oper_location` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作地点',
  `oper_param` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '返回参数',
  `status` int DEFAULT '0' COMMENT '操作状态（0正常 1异常）',
  `error_msg` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '错误消息',
  `oper_time` datetime DEFAULT NULL COMMENT '操作时间',
  `cost_time` bigint DEFAULT '0' COMMENT '消耗时间',
  PRIMARY KEY (`oper_id`),
  KEY `idx_sys_oper_log_bt` (`business_type`),
  KEY `idx_sys_oper_log_s` (`status`),
  KEY `idx_sys_oper_log_ot` (`oper_time`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_oper_log`
--

LOCK TABLES `sys_oper_log` WRITE;
/*!40000 ALTER TABLE `sys_oper_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_oper_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_post`
--

DROP TABLE IF EXISTS `sys_post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_post` (
  `post_id` bigint NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
  `post_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位编码',
  `post_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位名称',
  `post_sort` int NOT NULL COMMENT '显示顺序',
  `status` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='岗位信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_post`
--

LOCK TABLES `sys_post` WRITE;
/*!40000 ALTER TABLE `sys_post` DISABLE KEYS */;
INSERT INTO `sys_post` VALUES (1,'admin','管理员',1,'0','admin','2026-06-20 00:43:42','',NULL,'');
/*!40000 ALTER TABLE `sys_post` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role`
--

DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `role_id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) COLLATE utf8mb4_general_ci DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `menu_check_strictly` tinyint(1) DEFAULT '1' COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) DEFAULT '1' COMMENT '部门树选择项是否关联显示',
  `status` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role`
--

LOCK TABLES `sys_role` WRITE;
/*!40000 ALTER TABLE `sys_role` DISABLE KEYS */;
INSERT INTO `sys_role` VALUES (1,'超级管理员','admin',1,'1',1,1,'0','0','admin','2026-06-20 00:43:42','',NULL,'超级管理员'),(2,'客户','customer',2,'2',0,0,'0','0','admin','2026-06-22 00:00:00','',NULL,'客户提交需求专用角色'),(100,'下属管理员','employee',3,'1',1,1,'0','0','admin','2026-11-02 00:00:00','',NULL,'接单系统下属管理员，只能看到自己名下的项目');
/*!40000 ALTER TABLE `sys_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_dept`
--

DROP TABLE IF EXISTS `sys_role_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_dept` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`,`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色和部门关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_dept`
--

LOCK TABLES `sys_role_dept` WRITE;
/*!40000 ALTER TABLE `sys_role_dept` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_role_dept` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_menu`
--

DROP TABLE IF EXISTS `sys_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色和菜单关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_menu`
--

LOCK TABLES `sys_role_menu` WRITE;
/*!40000 ALTER TABLE `sys_role_menu` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_role_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `user_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `user_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户账号',
  `nick_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户昵称',
  `user_type` varchar(2) COLLATE utf8mb4_general_ci DEFAULT '00' COMMENT '用户类型（00系统用户）',
  `email` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户邮箱',
  `phonenumber` varchar(11) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '手机号码',
  `sex` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '头像地址',
  `password` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '密码',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '账号状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `login_ip` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime DEFAULT NULL COMMENT '最后登录时间',
  `pwd_update_date` datetime DEFAULT NULL COMMENT '密码最后更新时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user`
--

LOCK TABLES `sys_user` WRITE;
/*!40000 ALTER TABLE `sys_user` DISABLE KEYS */;

/*!40000 ALTER TABLE `sys_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user_post`
--

DROP TABLE IF EXISTS `sys_user_post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_post` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  PRIMARY KEY (`user_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户与岗位关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user_post`
--

LOCK TABLES `sys_user_post` WRITE;
/*!40000 ALTER TABLE `sys_user_post` DISABLE KEYS */;

/*!40000 ALTER TABLE `sys_user_post` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user_role`
--

DROP TABLE IF EXISTS `sys_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户和角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user_role`
--

LOCK TABLES `sys_user_role` WRITE;
/*!40000 ALTER TABLE `sys_user_role` DISABLE KEYS */;

/*!40000 ALTER TABLE `sys_user_role` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-06-21 15:40:40

-- Independent pre-sales quotations: safe to rerun; existing orders remain untouched.
CREATE TABLE IF NOT EXISTS jiedan_quote (
 id bigint NOT NULL AUTO_INCREMENT,
 title varchar(200) NOT NULL,
 requirement text,
 attachments longtext,
 employee_id bigint NOT NULL,
 creator_id bigint NOT NULL,
 amount decimal(12,2) DEFAULT NULL,
 quote_note text,
 status varchar(20) NOT NULL DEFAULT 'pending',
 order_id bigint DEFAULT NULL,
 version int NOT NULL DEFAULT 1,
 notice_version int NOT NULL DEFAULT 1,
 last_sent_time datetime DEFAULT NULL,
 source_name varchar(20) DEFAULT NULL,
 source_seq int unsigned DEFAULT NULL,
 UNIQUE KEY uq_quote_source_seq(source_name,source_seq),
 deleted tinyint NOT NULL DEFAULT 0,
 create_time datetime NOT NULL,
 update_time datetime NOT NULL,
 PRIMARY KEY (id),
 KEY idx_quote_employee (employee_id,deleted,status),
 KEY idx_quote_status (deleted,status,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS jiedan_quote_read (
    quote_id bigint NOT NULL,
    member_id bigint NOT NULL,
    read_version int NOT NULL,
    PRIMARY KEY (quote_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE IF NOT EXISTS `jiedan_mention` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `order_id` bigint NOT NULL COMMENT '项目ID',
  `timeline_id` bigint NOT NULL COMMENT '内部记录ID',
  `member_id` bigint NOT NULL COMMENT '被 @ 的成员',
  `from_id` bigint DEFAULT NULL COMMENT '谁 @ 的',
  `from_name` varchar(64) DEFAULT '' COMMENT '谁 @ 的（显示名）',
  `content` varchar(200) DEFAULT '' COMMENT '摘要',
  `create_time` datetime NOT NULL COMMENT '时间',
  `read_time` datetime DEFAULT NULL COMMENT '被 @ 的人打开项目的时间，空=未读',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_mention_member_read` (`member_id`, `read_time`),
  KEY `idx_jiedan_mention_order` (`order_id`),
  KEY `idx_jiedan_mention_timeline` (`timeline_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部记录 @ 提醒';
CREATE TABLE IF NOT EXISTS jiedan_webpush_key (
  id tinyint PRIMARY KEY,
  public_key varchar(100) NOT NULL,
  private_key varchar(60) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS jiedan_webpush_device (
  endpoint_hash char(64) PRIMARY KEY,
  member_id bigint NOT NULL,
  binding char(36) NOT NULL,
  endpoint varchar(2048) NOT NULL,
  p256dh varchar(100) NOT NULL,
  auth_key varchar(32) NOT NULL,
  KEY member_devices(member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS jiedan_quote_message (
 id bigint NOT NULL AUTO_INCREMENT,
 quote_id bigint NOT NULL,
 employee_id bigint NOT NULL,
 author_id bigint NOT NULL,
 author_name varchar(128) NOT NULL,
 recipient_id bigint DEFAULT NULL,
 kind varchar(12) NOT NULL,
 content text,
 attachments mediumtext,
 amount decimal(12,2) DEFAULT NULL,
 client_key varchar(64) DEFAULT NULL,
 created_at datetime DEFAULT NULL,
 read_at datetime DEFAULT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_quote_message_client (quote_id,author_id,client_key),
 KEY idx_quote_message_thread (quote_id,employee_id,id),
 KEY idx_quote_message_inbox (quote_id,recipient_id,read_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS jiedan_quote_counter (
 source_name varchar(20) NOT NULL,
 last_number int unsigned NOT NULL,
 PRIMARY KEY(source_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS jiedan_pending_receipt (
    order_id bigint NOT NULL,
    task_key varchar(32) NOT NULL,
    recipient_id bigint NOT NULL,
    first_entry bigint NOT NULL,
    sent_entry bigint DEFAULT NULL,
    sent_at datetime DEFAULT NULL,
    PRIMARY KEY (order_id, task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS jiedan_intake_receipt (
    customer_id bigint NOT NULL,
    request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash char(64) CHARACTER SET ascii NOT NULL,
    order_id bigint DEFAULT NULL,
    bug_id bigint DEFAULT NULL,
    PRIMARY KEY (customer_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户需求提交回执';

CREATE TABLE IF NOT EXISTS jiedan_bug_reply_meta (
    update_id bigint NOT NULL,
    bug_id bigint NOT NULL,
    sender_kind varchar(12) CHARACTER SET ascii NOT NULL,
    sender_id bigint NOT NULL,
    request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    payload_hash char(64) CHARACTER SET ascii DEFAULT NULL,
    PRIMARY KEY (update_id),
    UNIQUE KEY uq_bug_reply_request (bug_id, sender_id, request_key),
    KEY idx_bug_reply_team (bug_id, sender_kind, update_id),
    CONSTRAINT fk_bug_reply_meta_update FOREIGN KEY (update_id) REFERENCES jiedan_bug_update(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS jiedan_customer_issue_seen (
    customer_id bigint NOT NULL,
    bug_id bigint NOT NULL,
    update_id bigint NOT NULL,
    PRIMARY KEY (customer_id, bug_id),
    KEY idx_issue_seen_bug (bug_id),
    CONSTRAINT fk_issue_seen_bug FOREIGN KEY (bug_id) REFERENCES jiedan_bug(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS jiedan_issue_review (
    update_id bigint NOT NULL,
    bug_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash char(64) CHARACTER SET ascii NOT NULL,
    prior_status varchar(20) NOT NULL,
    state varchar(12) CHARACTER SET ascii NOT NULL DEFAULT 'pending',
    before_image text,
    after_image text,
    note text,
    decision_hash char(64) CHARACTER SET ascii,
    PRIMARY KEY (update_id),
    UNIQUE KEY uq_issue_review_request (bug_id, sender_id, request_key),
    KEY idx_issue_review_latest (bug_id, update_id),
    CONSTRAINT fk_issue_review_update FOREIGN KEY (update_id) REFERENCES jiedan_bug_update(id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_review_bug FOREIGN KEY (bug_id) REFERENCES jiedan_bug(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS jiedan_agent_link (
  order_id bigint NOT NULL,
  token_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_by bigint NOT NULL,
  created_at datetime NOT NULL,
  expires_at datetime NOT NULL,
  last_read_at datetime DEFAULT NULL,
  disabled tinyint NOT NULL DEFAULT 0,
  read_window varchar(450) CHARACTER SET ascii NOT NULL DEFAULT '',
  PRIMARY KEY (order_id),
  UNIQUE KEY uq_agent_token (token_hash),
  FOREIGN KEY (order_id) REFERENCES jiedan_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='jiedan_bug' AND column_name='sender_kind'), 'SELECT 1', 'ALTER TABLE jiedan_bug ADD sender_kind varchar(12) DEFAULT NULL');
PREPARE sheet_stmt FROM @ddl;
EXECUTE sheet_stmt;
DEALLOCATE PREPARE sheet_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='jiedan_bug' AND column_name='sheet_chars'), 'SELECT 1', 'ALTER TABLE jiedan_bug ADD sheet_chars int DEFAULT NULL');
PREPARE sheet_stmt FROM @ddl;
EXECUTE sheet_stmt;
DEALLOCATE PREPARE sheet_stmt;

-- Old customer intake receipts identify the sender even when the project was subsequently rebound.
-- Never rewrite the original issue, author or attachments.
UPDATE jiedan_bug b JOIN jiedan_intake_receipt r ON r.bug_id=b.id AND r.order_id=b.order_id
LEFT JOIN sys_user u ON u.user_id=r.customer_id
SET b.sender_kind=IF(b.created_by=u.user_name, 'old_account', 'old_intake')
WHERE b.sender_kind IS NULL;
CREATE TABLE IF NOT EXISTS jiedan_agent_reply_key (
 order_id bigint NOT NULL PRIMARY KEY,
 secret_hash char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL UNIQUE,
 reader_id bigint DEFAULT NULL,
 read_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY (order_id) REFERENCES jiedan_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
