-- =============================================================
-- Stage 4 参考解：对账表 order_stat（幂等建表）。
-- （参考解仅存在于 solutions 分支，勿合入 main）
--
-- 为什么必须有这张表：对账/统计需要一个"口径落点"。两条聚合 SQL 算出来的
-- order_count / amount_sum 要能持续累加，就靠 uk_product 唯一键 + upsert 实现。
-- 引擎必须是 InnoDB：只有它才有行锁 / MVCC / 间隙锁，本阶段要观察的
-- "未提交不可见""快照读 vs 当前读"在 MyISAM 上根本不存在。
-- =============================================================
CREATE TABLE IF NOT EXISTS order_stat (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  product_id  BIGINT        NOT NULL,
  order_count INT           NOT NULL DEFAULT 0,
  amount_sum  DECIMAL(12,2) NOT NULL DEFAULT 0,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

SELECT 1;
