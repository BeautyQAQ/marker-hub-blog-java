-- marker-hub：数据库与初始表结构
-- 目标连接：DBX / nas-mysql；数据库名：marker-hub。
-- 2026-10-29 用户已手工执行，通过 DBX HTTP MCP 只读验收：MySQL 8.1.0，库/表已创建。
-- SHOW CREATE 已确认字段、InnoDB、utf8mb4_unicode_ci、索引和外键与脚本一致。
-- 使用 MySQL 5.7+/8.x 通用语法；请在确认目标连接后执行整个文件。
-- 对应现有 Java 实体：User -> m_user，Blog -> m_blog。
-- 不删除现有数据库/表，不插入默认账号，不修改已有表结构。
-- IF NOT EXISTS 只跳过已存在对象，不会校验或迁移其结构。

SET NAMES utf8mb4;
-- 仅设置本次执行会话；应用的每个数据库连接也需要统一使用 UTC。
SET SESSION time_zone = '+00:00';

-- 数据库名含连字符，所有引用均需使用反引号。
CREATE DATABASE IF NOT EXISTS `marker-hub`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `marker-hub`;

-- 用户状态与 AccountRealm 兼容：0=正常，-1=锁定。
-- password 仅存应用生成的密码摘要；本脚本不预置密码。
-- utf8mb4_unicode_ci 下用户名唯一比较不区分大小写。
CREATE TABLE IF NOT EXISTS `marker-hub`.`m_user` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID，对应Java Long',
    `username`   VARCHAR(64)  NOT NULL COMMENT '登录名，不区分大小写唯一',
    `avatar`     VARCHAR(512) NULL DEFAULT NULL COMMENT '头像URL',
    `email`      VARCHAR(254) NULL DEFAULT NULL COMMENT '邮箱，未填写时使用NULL',
    `password`   VARCHAR(255) NOT NULL COMMENT '密码摘要，不存明文',
    `status`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常，-1=锁定',
    `created`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，约定UTC',
    `last_login` DATETIME     NULL DEFAULT NULL COMMENT '最后登录时间，约定UTC',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_m_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '博客用户';

-- 文章状态是本次新约定，现有后端尚未实现其业务逻辑：
-- 0=草稿，1=已发布，-1=已删除（软删除）；公开查询必须限定 status=1。
-- 新建默认为草稿，发布接口必须显式设置 status=1。
-- 使用注释约定状态，状态合法性需由后端校验，不依赖旧版MySQL的CHECK。
CREATE TABLE IF NOT EXISTS `marker-hub`.`m_blog` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '文章ID，对应Java Long',
    `user_id`     BIGINT        NOT NULL COMMENT '作者ID，对应m_user.id',
    `title`       VARCHAR(255)  NOT NULL COMMENT '文章标题',
    `description` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '文章摘要',
    `content`     MEDIUMTEXT    NOT NULL COMMENT 'Markdown原文，非渲染后的HTML',
    `created`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，约定UTC',
    `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '0=草稿，1=已发布，-1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_m_blog_status_created_id` (`status`, `created`, `id`),
    KEY `idx_m_blog_user_created_id` (`user_id`, `created`, `id`),
    CONSTRAINT `fk_m_blog_user`
        FOREIGN KEY (`user_id`) REFERENCES `marker-hub`.`m_user` (`id`)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '博客文章';

-- 执行后核对实际建库/建表结果；已有对象被跳过时尤其需要检查。
SHOW CREATE DATABASE `marker-hub`;
SHOW CREATE TABLE `marker-hub`.`m_user`;
SHOW CREATE TABLE `marker-hub`.`m_blog`;
