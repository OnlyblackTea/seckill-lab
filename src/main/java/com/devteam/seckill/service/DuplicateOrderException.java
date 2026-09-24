package com.devteam.seckill.service;

/**
 * 用户对同一商品重复下单。
 *
 * <p>{@code seckill_order} 表上有唯一键 {@code uk_user_product (user_id, product_id)}，
 * 重复插入会让 MySQL 抛出 {@code SQLIntegrityConstraintViolationException}，
 * 被 Spring 翻译成 {@code DuplicateKeyException}。本异常是业务层对它的封装。</p>
 *
 * <p>由 Stage 2（事务原子性）使用：它同时是**验证回滚的天然故障注入点**——
 * 抛异常之前库存已经被扣减，如果扣减和建单不在同一事务里，库存就会少一件却查不到订单。</p>
 */
public class DuplicateOrderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateOrderException(Long userId, Long productId) {
        super("重复下单：userId=" + userId + ", productId=" + productId);
    }
}
