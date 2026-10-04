-- =============================================================
-- 事实检测（FactCheck）MySQL 建表语句
-- MySQL 版本：8.0
-- 字符集：utf8mb4 / utf8mb4_unicode_ci
-- 引擎：InnoDB
-- 说明：
--   1) 按产品文档与技术方案 v1.1 设计，共 12 张表。
--   2) 不创建物理外键，由 Java 代码保证数据一致性（参考 sky-take-out 风格）。
--   3) 本脚本可重复执行（先 DROP 后 CREATE，会清空表数据，请谨慎）。
-- =============================================================

CREATE DATABASE IF NOT EXISTS fact_check DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE fact_check;

DROP TABLE IF EXISTS t_content_safety_log;
DROP TABLE IF EXISTS t_record_rating;
DROP TABLE IF EXISTS t_user_favorite;
DROP TABLE IF EXISTS t_message;
DROP TABLE IF EXISTS t_operation_log;
DROP TABLE IF EXISTS t_conclusion_revision;
DROP TABLE IF EXISTS t_authority_info;
DROP TABLE IF EXISTS t_appeal;
DROP TABLE IF EXISTS t_detect_log;
DROP TABLE IF EXISTS t_evidence;
DROP TABLE IF EXISTS t_fact_check_record;
DROP TABLE IF EXISTS t_user;

-- ------------------------------------------------------------
-- 1. t_user 用户表
-- 普通用户与管理员同表，通过 role 区分；微信小程序 openid 绑定本表实现账号同步
-- ------------------------------------------------------------
CREATE TABLE t_user (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    username        VARCHAR(50)     NOT NULL COMMENT '用户名',
    password        VARCHAR(100)    NOT NULL COMMENT 'BCrypt 密码',
    nickname        VARCHAR(50)     DEFAULT NULL COMMENT '昵称',
    avatar          VARCHAR(255)    DEFAULT NULL COMMENT '头像 OSS URL',
    role            TINYINT         NOT NULL DEFAULT 0 COMMENT '0 普通用户，1 管理员',
    status          TINYINT         NOT NULL DEFAULT 1 COMMENT '0 禁用，1 正常，2 已注销',
    wechat_openid   VARCHAR(64)     DEFAULT NULL COMMENT '微信 openid（S11）',
    wechat_unionid  VARCHAR(64)     DEFAULT NULL COMMENT '微信 unionid（S11）',
    last_login_time DATETIME        DEFAULT NULL COMMENT '最近登录时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_wechat_openid (wechat_openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. t_fact_check_record 检测记录表
-- 一条待检测消息对应一条记录；content_hash 用于精确去重
-- ------------------------------------------------------------
CREATE TABLE t_fact_check_record (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT          NOT NULL COMMENT '首个提交用户 ID',
    content         TEXT            NOT NULL COMMENT '规范化后的待检测文本',
    content_hash    CHAR(64)        NOT NULL COMMENT 'SHA-256 哈希，精确去重',
    category        VARCHAR(50)     NOT NULL DEFAULT '其他' COMMENT '分类：健康/社会/财经/科技/政策/其他（S5）',
    source_type     TINYINT         NOT NULL DEFAULT 1 COMMENT '1 文字，2 图片',
    image_url       VARCHAR(255)    DEFAULT NULL COMMENT '图片 OSS URL（文字为空）',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0 检测中，1 待人工诊断，2 已完成，3 检测失败',
    verdict         TINYINT         DEFAULT NULL COMMENT '1 真，2 假，3 无法判定',
    verdict_source  TINYINT         DEFAULT NULL COMMENT '1 AI 判定，2 人工判定',
    confidence      DECIMAL(3,2)    DEFAULT NULL COMMENT 'AI 置信度 0.00-1.00',
    reasoning_chain TEXT            DEFAULT NULL COMMENT '推理链',
    ai_summary      VARCHAR(500)    DEFAULT NULL COMMENT 'AI 无法判定时给管理员的原因说明',
    version         INT             NOT NULL DEFAULT 1 COMMENT '结论版本号',
    hit_count       INT             NOT NULL DEFAULT 1 COMMENT '被去重命中的次数',
    assignee_id     BIGINT          DEFAULT NULL COMMENT '当前处理管理员 ID（S14 转派）',
    deadline        DATETIME        DEFAULT NULL COMMENT '人工诊断时限，默认创建后 48h（S14）',
    authority_id    BIGINT          DEFAULT NULL COMMENT '命中的权威信息 ID（S19）',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    complete_time   DATETIME        DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_hash (content_hash),
    KEY idx_user_status (user_id, status, create_time),
    KEY idx_status_create_time (status, create_time),
    KEY idx_category_status (category, status),
    KEY idx_assignee_status (assignee_id, status),
    KEY idx_status_deadline (status, deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='检测记录表';

-- ------------------------------------------------------------
-- 3. t_evidence 证据表
-- 大模型搜索或管理员补充的证据，含来源可信度
-- ------------------------------------------------------------
CREATE TABLE t_evidence (
    id                  BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_id           BIGINT          NOT NULL COMMENT '检测记录 ID',
    title               VARCHAR(500)    DEFAULT NULL COMMENT '标题',
    url                 VARCHAR(500)    DEFAULT NULL COMMENT '链接',
    snippet             TEXT            DEFAULT NULL COMMENT '摘要',
    source              VARCHAR(100)    DEFAULT NULL COMMENT '来源站点/媒体',
    -- 允许 NULL 是刻意的：管理员人工补充的证据**不臆造**可信度/相关度分值，
    -- 存 NULL 表示"无此数据"，前端显示 "-"；存 0.00 会被误读成"0 分（极不可信）"。
    -- 早前这里写的是 NOT NULL DEFAULT 0.00，导致 AdminRecordServiceImpl.addEvidence
    -- 显式写入 null 时被 MySQL 拒绝（Column 'source_credibility' cannot be null），
    -- "补充证据"功能实际不可用。已建库请执行 sql/migrations/ 下的迁移脚本。
    source_credibility  DECIMAL(3,2)    DEFAULT NULL COMMENT '来源可信度 0-1（S13）；NULL=未评分',
    relevance_score     DECIMAL(5,4)    DEFAULT NULL COMMENT '相关度 0-1；NULL=未评分',
    source_type         TINYINT         NOT NULL DEFAULT 1 COMMENT '1 AI 搜索，2 管理员添加',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_record_id (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='证据表';

-- ------------------------------------------------------------
-- 4. t_detect_log 检测请求日志表
-- 每次用户提交检测都记录，用于审计、防刷分析、热度统计辅助
-- ------------------------------------------------------------
CREATE TABLE t_detect_log (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT          NOT NULL COMMENT '用户 ID',
    content         TEXT            NOT NULL COMMENT '提交内容',
    content_hash    CHAR(64)        NOT NULL COMMENT 'SHA-256 哈希',
    category        VARCHAR(50)     NOT NULL DEFAULT '其他' COMMENT '分类（S5）',
    source_type     TINYINT         NOT NULL DEFAULT 1 COMMENT '1 文字，2 图片',
    dedup_hit       TINYINT         NOT NULL DEFAULT 0 COMMENT '0 未命中，1 精确命中，2 语义命中',
    hit_record_id   BIGINT          DEFAULT NULL COMMENT '命中的历史记录 ID',
    record_id       BIGINT          DEFAULT NULL COMMENT '新建的记录 ID（未命中时）',
    ip              VARCHAR(45)     DEFAULT NULL COMMENT '客户端 IP',
    user_agent      VARCHAR(255)    DEFAULT NULL COMMENT '浏览器 UA',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_content_hash_time (content_hash, create_time),
    KEY idx_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='检测请求日志表';

-- ------------------------------------------------------------
-- 5. t_appeal 申诉表
-- ------------------------------------------------------------
CREATE TABLE t_appeal (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_id       BIGINT          NOT NULL COMMENT '被申诉的记录 ID',
    user_id         BIGINT          NOT NULL COMMENT '申诉人 ID',
    reason_type     TINYINT         NOT NULL COMMENT '1 结论错误，2 证据不足，3 来源不可信，4 其他',
    content         VARCHAR(500)    DEFAULT NULL COMMENT '申诉说明',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0 待处理，1 已采纳，2 已驳回',
    handle_result   VARCHAR(500)    DEFAULT NULL COMMENT '管理员处理说明',
    handled_by      BIGINT          DEFAULT NULL COMMENT '处理管理员 ID',
    handle_time     DATETIME        DEFAULT NULL COMMENT '处理时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_record_id (record_id),
    KEY idx_status_time (status, create_time),
    KEY idx_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='申诉表';

-- ------------------------------------------------------------
-- 6. t_authority_info 权威信息表
-- ------------------------------------------------------------
CREATE TABLE t_authority_info (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    title           VARCHAR(200)    NOT NULL COMMENT '标题',
    content         TEXT            NOT NULL COMMENT '正文',
    category        VARCHAR(50)     DEFAULT NULL COMMENT '分类（健康/政策/社会/财经/科技等）',
    source          VARCHAR(200)    DEFAULT NULL COMMENT '来源/发布机构',
    source_url      VARCHAR(500)    DEFAULT NULL COMMENT '原文链接',
    cover_image     VARCHAR(255)    DEFAULT NULL COMMENT '封面图 OSS URL',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0 草稿，1 已发布，2 已下线',
    publish_time    DATETIME        DEFAULT NULL COMMENT '发布时间',
    create_by       BIGINT          DEFAULT NULL COMMENT '创建人',
    update_by       BIGINT          DEFAULT NULL COMMENT '更新人',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_status_publish (status, publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权威信息表';

-- ------------------------------------------------------------
-- 7. t_conclusion_revision 结论修订表
-- 管理员修改结论或申诉采纳时记录版本快照
-- ------------------------------------------------------------
CREATE TABLE t_conclusion_revision (
    id                      BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_id               BIGINT          NOT NULL COMMENT '记录 ID',
    old_verdict             TINYINT         DEFAULT NULL COMMENT '旧结论',
    new_verdict             TINYINT         DEFAULT NULL COMMENT '新结论',
    old_reasoning_chain     TEXT            DEFAULT NULL COMMENT '旧推理链快照',
    new_reasoning_chain     TEXT            DEFAULT NULL COMMENT '新推理链',
    reason                  VARCHAR(500)    NOT NULL COMMENT '修改原因',
    source                  TINYINT         NOT NULL COMMENT '1 管理员主动修改，2 申诉采纳',
    appeal_id               BIGINT          DEFAULT NULL COMMENT '关联申诉 ID',
    admin_id                BIGINT          NOT NULL COMMENT '操作管理员 ID',
    create_time             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_record_id (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结论修订表';

-- ------------------------------------------------------------
-- 8. t_operation_log 操作日志表
-- ------------------------------------------------------------
CREATE TABLE t_operation_log (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    admin_id        BIGINT          DEFAULT NULL COMMENT '管理员 ID',
    operation       VARCHAR(100)    DEFAULT NULL COMMENT '操作描述',
    method          VARCHAR(200)    DEFAULT NULL COMMENT '方法名',
    params          TEXT            DEFAULT NULL COMMENT '请求参数 JSON',
    ip              VARCHAR(45)     DEFAULT NULL COMMENT 'IP',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_admin_time (admin_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';

-- ------------------------------------------------------------
-- 9. t_message 站内信表（S7）
-- 系统自动发送的站内通知
-- ------------------------------------------------------------
CREATE TABLE t_message (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT          NOT NULL COMMENT '接收用户 ID',
    type            TINYINT         NOT NULL COMMENT '1 检测完成，2 转人工，3 人工诊断完成，4 申诉结果，5 结论被修改，6 系统通知',
    title           VARCHAR(200)    DEFAULT NULL COMMENT '标题',
    content         VARCHAR(1000)   DEFAULT NULL COMMENT '内容',
    related_id      BIGINT          DEFAULT NULL COMMENT '关联记录/申诉 ID',
    is_read         TINYINT         NOT NULL DEFAULT 0 COMMENT '0 未读，1 已读',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_read (user_id, is_read, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内信表';

-- ------------------------------------------------------------
-- 10. t_user_favorite 收藏表（S12）
-- ------------------------------------------------------------
CREATE TABLE t_user_favorite (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT          NOT NULL COMMENT '用户 ID',
    record_id       BIGINT          NOT NULL COMMENT '检测记录 ID',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_record (user_id, record_id),
    KEY idx_record_id (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';

-- ------------------------------------------------------------
-- 11. t_record_rating 结果评分表（S6）
-- 同一用户对同一记录保留一条评分，可修改
-- ------------------------------------------------------------
CREATE TABLE t_record_rating (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT          NOT NULL COMMENT '用户 ID',
    record_id       BIGINT          NOT NULL COMMENT '检测记录 ID',
    rating          TINYINT         NOT NULL COMMENT '1 有帮助，0 无帮助',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_record (user_id, record_id),
    KEY idx_record_id (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结果评分表';

-- ------------------------------------------------------------
-- 12. t_content_safety_log 内容安全审核日志表（S2）
-- ------------------------------------------------------------
CREATE TABLE t_content_safety_log (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT          NOT NULL COMMENT '用户 ID',
    content_hash    CHAR(64)        DEFAULT NULL COMMENT '内容哈希',
    source_type     TINYINT         NOT NULL COMMENT '1 文字，2 图片',
    check_type      VARCHAR(50)     DEFAULT NULL COMMENT '审核类型（text/image）',
    result          TINYINT         NOT NULL COMMENT '0 通过，1 拒绝',
    reason          VARCHAR(500)    DEFAULT NULL COMMENT '拒绝原因',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, create_time),
    KEY idx_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容安全审核日志表';
