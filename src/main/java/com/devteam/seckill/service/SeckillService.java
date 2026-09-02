package com.devteam.seckill.service;

import com.devteam.seckill.domain.SeckillOrder;
import org.springframework.stereotype.Service;

/**
 * 秒杀下单服务。
 *
 * <p>Stage 0 只提供骨架：真正的"扣库存 + 建订单"原子逻辑在 Stage 2（事务）与
 * Stage 3（锁 / 防超卖）由你亲手实现。现在调用会明确抛出"工单未解锁"。</p>
 */
@Service
public class SeckillService {

    /**
     * @param userId    下单用户
     * @param productId 秒杀商品
     * @return 生成的订单
     */
    public SeckillOrder seckill(Long userId, Long productId) {
        // TODO(Stage 2): 用 @Transactional 保证"扣库存 + 建订单"原子性。
        // TODO(Stage 3): 用悲观锁(SELECT ... FOR UPDATE)或乐观锁(version / stock=stock-1 where stock>0)防超卖。
        throw new UnsupportedOperationException("秒杀下单逻辑尚未实现：Stage 2/3 工单待解锁");
    }
}
