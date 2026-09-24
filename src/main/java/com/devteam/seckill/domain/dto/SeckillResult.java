package com.devteam.seckill.domain.dto;

/**
 * 秒杀下单结果（Stage 2/3 的返回载体）。
 *
 * <p>为什么要一个结果对象而不是单纯返回订单 id：因为"售罄"和"成功"都必须携带
 * 业务上下文（剩余库存、失败原因）给上层和并发模拟器，用返回值表达比用异常表达
 * 更容易统计（模拟器要数"成功多少 / 售罄多少"）。</p>
 *
 * @param success        是否下单成功
 * @param orderId        成功时的订单 id；失败为 {@code null}
 * @param remainingStock 成功时的剩余库存；失败为 {@code null}
 * @param reason         失败原因（成功为 {@code null}）
 */
public record SeckillResult(boolean success, Long orderId, Integer remainingStock, String reason) {

    public static SeckillResult ok(Long orderId, Integer remainingStock) {
        return new SeckillResult(true, orderId, remainingStock, null);
    }

    public static SeckillResult soldOut(Long productId) {
        return new SeckillResult(false, null, null, "SOLD_OUT:" + productId);
    }

    public static SeckillResult duplicate(Long userId, Long productId) {
        return new SeckillResult(false, null, null, "DUPLICATE:" + userId + ":" + productId);
    }
}
