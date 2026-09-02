-- =============================================================
-- 01_schema.sql  —  建库建表（容器首次初始化时按文件名顺序执行）
-- 库名：seckill_lab
-- 设计说明：
--   * product 表故意【不建】用于"分类+状态+价格"搜索的二级索引，
--     只保留主键。Stage 1 的工单就是让你亲手补上合适的索引并用
--     EXPLAIN 证明它命中。
--   * 所有金额用 DECIMAL，避免浮点误差；库存 stock 用 INT。
--   * version 字段为 Stage 3 乐观锁预留。
-- =============================================================

CREATE DATABASE IF NOT EXISTS seckill_lab
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE seckill_lab;

DROP TABLE IF EXISTS seckill_order;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS `user`;

-- 用户表
CREATE TABLE `user` (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 商品表（含秒杀库存）
CREATE TABLE product (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  name        VARCHAR(128)  NOT NULL,
  category_id BIGINT        NOT NULL,
  status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1=上架 0=下架',
  price       DECIMAL(10,2) NOT NULL,
  stock       INT           NOT NULL DEFAULT 0,
  version     INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号(Stage3)',
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
  -- 注意：这里【故意】没有 (category_id, status, price) 之类的二级索引。
  -- Stage 1 由你来加。
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 秒杀订单表
CREATE TABLE seckill_order (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  user_id    BIGINT        NOT NULL,
  product_id BIGINT        NOT NULL,
  quantity   INT           NOT NULL DEFAULT 1,
  amount     DECIMAL(10,2) NOT NULL,
  status     TINYINT       NOT NULL DEFAULT 0 COMMENT '0=已创建 1=已支付 2=已取消',
  created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_product (user_id, product_id) COMMENT '防重复下单'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
