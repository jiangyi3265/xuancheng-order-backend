-- ============================================================
-- 项目记事本（内部台账）：老板/员工随时记录客户沟通、需求，可贴图片/文档
-- 客户端不可见。
--
-- 【适用场景】仅用于「已导入旧版 xc_jiedan.sql 的存量数据库」升级。
-- 全新安装请直接导入最新 xc_jiedan.sql（已含以下列）。
-- ============================================================

ALTER TABLE jiedan_order
    ADD COLUMN notes LONGTEXT NULL COMMENT '项目记事本正文(内部)' AFTER attachments,
    ADD COLUMN note_attachments LONGTEXT NULL COMMENT '记事本附件(JSON,内部)' AFTER notes;
