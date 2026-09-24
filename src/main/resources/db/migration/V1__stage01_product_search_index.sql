-- =============================================================
-- Stage 1 工单：为 product 表的"分类 + 上架状态 + 价格区间"搜索加索引。
-- 这个文件会被 stage01 验收测试用 @Sql 自动执行（每次跑测试前）。
--
-- 索引：idx_product_search (category_id, status, price)
--   列序理由（最左前缀）：
--     * category_id、status 是等值谓词 => 放最左，精确定位索引区间（type=ref）；
--     * price 既是范围谓词（BETWEEN）又是 ORDER BY 列 => 放最后，
--       索引在等值前缀后按 price 有序，范围扫描天然有序，
--       ORDER BY price ASC 可直接复用索引顺序（没有 Using filesort）。
--   为什么不是 (category_id, price, status)：范围列 price 之后的 status 用不上索引，
--   status 的过滤会退化成回表后再过滤（Using where），区间也更宽。
--
-- 幂等：先查 information_schema，不存在才 ADD INDEX，重复执行不报 Duplicate key name。
-- =============================================================
SET @exist := (SELECT COUNT(1) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name   = 'product'
                 AND index_name   = 'idx_product_search');
SET @ddl := IF(@exist = 0,
               'ALTER TABLE product ADD INDEX idx_product_search (category_id, status, price)',
               'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SELECT 1;