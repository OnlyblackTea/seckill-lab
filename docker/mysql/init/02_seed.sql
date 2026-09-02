-- =============================================================
-- 02_seed.sql  —  种子数据
--   1) 少量"手工"基础数据（id=1..3 的商品、几个用户），供冒烟测试稳定断言；
--   2) 存储过程批量灌入大量商品，让全表扫描在 Stage 1 明显变慢、
--      并让优化器有足够统计信息去选择索引。
-- 幂等：init 脚本只在数据卷为空(首次 up)时执行；重灌请 `docker compose down -v`。
-- =============================================================

USE seckill_lab;

-- ---- 基础用户 ----
INSERT INTO `user` (id, username, password_hash) VALUES
  (1, 'alice', 'x'), (2, 'bob', 'x'), (3, 'carol', 'x');

-- ---- 基础商品（id 固定，供冒烟/演示稳定引用）----
-- id=1 是"热点秒杀商品"，库存刻意设为 10，Stage 3 用它复现超卖。
INSERT INTO product (id, name, category_id, status, price, stock, version) VALUES
  (1, 'iPhone 秒杀专场', 10, 1, 4999.00, 10, 0),
  (2, '机械键盘',        10, 1,  399.00, 100, 0),
  (3, '人体工学椅',      20, 0, 1299.00, 50, 0);

-- ---- 批量商品：让 product 成为"大表" ----
-- 数据分布：category_id ∈ [100,149]（50 个分类），status ∈ {0,1}，price ∈ [1,370]
-- 这些 category_id 与上面的基础数据(10/20)错开，便于 Stage 1 EXPLAIN 用分类做选择性过滤。
DROP PROCEDURE IF EXISTS seed_bulk_products;

DELIMITER $$
CREATE PROCEDURE seed_bulk_products(IN n INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  WHILE i < n DO
    INSERT INTO product (name, category_id, status, price, stock, version)
    VALUES (
      CONCAT('bulk-product-', i),
      100 + (i % 50),
      i % 2,
      ROUND(1 + (i % 1000) * 0.37, 2),
      100 + (i % 500),
      0
    );
    SET i = i + 1;
  END WHILE;
END$$
DELIMITER ;

-- 灌 50000 行（首次初始化约需十几秒）。想更大/更快可改这个数字。
CALL seed_bulk_products(50000);

DROP PROCEDURE seed_bulk_products;

-- 刷新统计信息，帮助优化器做出正确的索引选择
ANALYZE TABLE product;
