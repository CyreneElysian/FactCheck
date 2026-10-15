-- =============================================================
-- 事实检测（FactCheck）初始化数据脚本
-- MySQL 版本：8.0
-- =============================================================
-- 用途：为新环境创建"第一个管理员账号"，解决"管理员只能由管理员创建"的启动死锁。
--
-- 执行顺序（**文件名前缀是有意的**）：
--   1) 01_fact_check_schema.sql  —— 建库建表（12 张表）
--   2) 02_fact_check_init.sql    —— 本脚本：创建初始管理员（必需）
--   3) scripts/generate_test_data.py —— 可选：生成演示数据（普通用户/记录/证据/热度）
--
-- ⚠️ 为什么文件名带 01/02 前缀：docker-compose 把整个 sql/ 目录挂到
--   /docker-entrypoint-initdb.d，MySQL 官方 entrypoint 会**按文件名字典序**逐个 source。
--   早前文件名叫 fact_check_init.sql / fact_check_schema.sql，字典序里 init 排在 schema **前面**，
--   于是全新 `docker compose up` 时初始化脚本先跑、找不到 t_user 而报错；
--   该报错在 entrypoint 的 `set -e` 下会让 MySQL 容器直接退出 → 整套 compose 卡在 healthcheck。
--   加数字前缀后字典序与文档顺序一致，问题不再出现。
--
-- 本脚本可重复执行（已存在则跳过，不会重复插入、不会覆盖已有密码）。
--
-- ⚠️ 安全提示：
--   1) 初始密码为演示用弱口令，请在首次登录后立即修改。
--   2) 生产环境请勿使用本脚本的默认密码。
--
-- 说明：消息分类（健康/社会/财经/科技/政策/其他）在代码中以固定枚举/常量实现，
--       数据库中没有分类字典表，因此本脚本不插入分类数据。
-- =============================================================

USE fact_check;

-- ------------------------------------------------------------
-- 前置检查：t_user 必须已存在（即 01_fact_check_schema.sql 已成功执行）
--   目的：万一建表没跑成功，这里给出一句明确的提示，
--   而不是让 INSERT 抛 "Table 'fact_check.t_user' doesn't exist" 让人猜原因。
-- ------------------------------------------------------------
SET @has_user_table := (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 't_user'
);
SET @sql := IF(@has_user_table = 0,
    'SELECT ''警告：未找到 t_user 表，请先执行 01_fact_check_schema.sql'' AS init_check',
    'SELECT ''前置检查通过：t_user 存在'' AS init_check');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 初始管理员
--   用户名：admin
--   密码：123456   （BCrypt 密文，已在本机用 POST /user/login 实测登录通过）
--   role=1 管理员，status=1 正常
--
-- ⚠️ 这是**弱口令**，仅用于本地开发/演示：
--   1) 首次登录后请立即修改；
--   2) 生产环境请改成强密码并妥善保管，切勿沿用本脚本的默认值。
--   3) 项目**已有**密码强度校验（`ValidationUtils.requireStrongPassword`：≥8 位、
--      须含字母与数字、拒绝常见弱口令），但该规则**只在注册与改密时生效**，
--      不会校验直接写进库的初始数据 —— 所以这里的弱口令不会被系统拦下，
--      只能靠部署时自觉更换。
--
-- 关于密文：这里存的是 BCrypt hash（$2a$10$，cost=10），与登录时
-- BCryptPasswordEncoder.matches() 校验的格式完全一致；同一个明文每次加盐结果不同，
-- 因此不要用"字符串相等"去核对密码，只能靠实际登录验证。
-- ------------------------------------------------------------
INSERT INTO t_user (username, password, nickname, role, status, create_time, update_time)
SELECT 'admin',
       '$2a$10$od3RbGsww2QYPk25xgMxCeXAbzbC8kzVBtde6yLMcXRSLLTzAUWz6',
       '系统管理员',
       1,
       1,
       NOW(),
       NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE username = 'admin');

-- ------------------------------------------------------------
-- 执行结果确认：应至少返回 1 行管理员记录
-- ------------------------------------------------------------
SELECT id, username, nickname, role, status, create_time
FROM t_user
WHERE role = 1
ORDER BY id;
