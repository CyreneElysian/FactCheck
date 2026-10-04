-- =============================================================
-- 迁移：2026-10-04 —— t_evidence 的可信度/相关度允许 NULL
-- MySQL 版本：8.0
-- =============================================================
-- 背景（一个真实缺陷）：
--   t_evidence.source_credibility 与 relevance_score 原定义为
--       NOT NULL DEFAULT 0.00 / NOT NULL DEFAULT 0.0000
--   而 AdminRecordServiceImpl.addEvidence 出于**产品意图**显式写入 NULL
--   （注释原文："人工补充的证据不臆造可信度/相关度分值：留空，前端显示 -"）。
--   两者冲突 → MySQL 报 "Column 'source_credibility' cannot be null"
--   → 全局异常处理器把它转成"数据完整性校验失败，请检查提交内容"
--   → **管理员补充证据功能实际不可用**。
--
-- 修复：把这两列改为可空（NULL 语义 = "未评分"，与前端显示的 "-" 一致）。
--   注意不能用 DEFAULT 救场：INSERT 语句显式带了这两列，MySQL 不会套用默认值。
--
-- 本脚本**可重复执行**（先查 information_schema，仅在仍是 NOT NULL 时才改）。
--
-- 用法：
--   mysql -u root -p < sql/migrations/2026-10-04_evidence_scores_nullable.sql
--   或（已用 docker-entrypoint-initdb.d 起过库的）直接进容器执行同一文件
--
-- 注意：01_fact_check_schema.sql（全新安装用）已同步包含该修复；只有**已建好的库**才需要跑本脚本。
-- =============================================================

USE fact_check;

-- ------------------------------------------------------------
-- source_credibility → 可空
-- ------------------------------------------------------------
SET @need := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_evidence'
      AND column_name = 'source_credibility'
      AND is_nullable = 'NO'
);
SET @sql := IF(@need > 0,
    'ALTER TABLE t_evidence MODIFY COLUMN source_credibility DECIMAL(3,2) DEFAULT NULL COMMENT ''来源可信度 0-1（S13）；NULL=未评分''',
    'SELECT ''source_credibility 已是可空，跳过'' AS migration_note');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- relevance_score → 可空
-- ------------------------------------------------------------
SET @need := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_evidence'
      AND column_name = 'relevance_score'
      AND is_nullable = 'NO'
);
SET @sql := IF(@need > 0,
    'ALTER TABLE t_evidence MODIFY COLUMN relevance_score DECIMAL(5,4) DEFAULT NULL COMMENT ''相关度 0-1；NULL=未评分''',
    'SELECT ''relevance_score 已是可空，跳过'' AS migration_note');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 结果确认：两列的 IS_NULLABLE 都应为 YES
-- ------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 't_evidence'
  AND column_name IN ('source_credibility', 'relevance_score');
