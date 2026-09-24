package com.devteam.seckill.mapper;

import com.devteam.seckill.domain.SeckillOrder;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * 订单数据访问接口。
 *
 * <p>Stage 2 补 {@code insertOrder}/{@code countByProduct}；
 * Stage 4 补 {@code sumAmountByProduct}/{@code upsertOrderStat}。</p>
 */
public interface OrderMapper {

    /**
     * Stage 2（你来实现）：插入订单，返回受影响行数。
     * 订单插入后，入参对象的 {@code id} 会被回填（XML 里的 {@code useGeneratedKeys}）。
     */
    int insertOrder(SeckillOrder order);

    /** Stage 2/3（你来实现）：按商品统计已创建订单数（用于校验是否超卖 / 对账）。 */
    int countByProduct(@Param("productId") Long productId);

    /** 按"用户 + 商品"统计订单数（唯一键 uk_user_product 的业务侧预检查）。 */
    int countByUserAndProduct(@Param("userId") Long userId, @Param("productId") Long productId);

    /** Stage 4（你来实现）：按商品汇总成交额（对账口径）。无订单时返回 0 而不是 null。 */
    BigDecimal sumAmountByProduct(@Param("productId") Long productId);

    /** Stage 4（你来实现）：把对账结果幂等写入 order_stat（存在则累加，靠 uk_product 兜底）。 */
    int upsertOrderStat(@Param("productId") Long productId,
                        @Param("orderCount") int orderCount,
                        @Param("amountSum") BigDecimal amountSum);

    /** 按主键查订单（测试与排查用）。 */
    SeckillOrder findById(@Param("id") Long id);
}
