package com.devteam.seckill.mapper;

import com.devteam.seckill.domain.SeckillOrder;
import org.apache.ibatis.annotations.Param;

/**
 * 订单数据访问接口。
 * Stage 2/3 才会用到；相关 SQL 目前在 XML 中是 TODO 占位（未绑定）。
 */
public interface OrderMapper {

    /** Stage 2（你来实现）：插入订单，返回受影响行数。 */
    int insertOrder(SeckillOrder order);

    /** Stage 3（你来实现）：按商品统计已创建订单数（用于校验是否超卖）。 */
    int countByProduct(@Param("productId") Long productId);
}
