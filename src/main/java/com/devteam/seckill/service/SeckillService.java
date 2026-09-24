package com.devteam.seckill.service;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.domain.SeckillOrder;
import com.devteam.seckill.domain.dto.SeckillResult;
import com.devteam.seckill.mapper.OrderMapper;
import com.devteam.seckill.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀下单服务。
 *
 * <h2>骨架现状：这是一个"能跑但会出事"的线上实现，Stage 2/3 就是来修它的</h2>
 * <ul>
 *   <li><b>Stage 2（事务）</b>：{@link #placeOrder} <b>没有</b> {@code @Transactional}。
 *       它依次调用两次 mapper，各自是独立事务（各自 autocommit）。所以"扣库存成功、建订单失败"时
 *       库存不会回滚 —— 这正是工单里"库存扣了订单没建、月底对账差几百单"的成因。
 *       你要做的是把这两步收进同一个事务边界，并想清楚哪些异常会触发回滚。</li>
 *   <li><b>Stage 3（超卖）</b>：扣减走的是"先 {@code selectStock} 读 → 应用里判断 &gt; 0 →
 *       再 {@code updateStock} 写回"三步。读与写之间有窗口，两个并发请求可能都读到 10、
 *       各写回 9 —— 丢失更新，最终卖出 11 件而库存还剩 9。
 *       你要做的是把"判断 + 扣减"合并成一条原子 SQL（或用悲观锁 / 乐观锁）。</li>
 * </ul>
 * 这两个缺陷是<b>故意保留</b>的教学样本：先红，你改对了再绿。
 */
@Service
public class SeckillService {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private LockService lockService;

    public SeckillService(ProductMapper productMapper, OrderMapper orderMapper) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
    }

    /** 秒杀下单（Stage 2 修事务 / Stage 3 修超卖）。 */
    public SeckillResult seckill(Long userId, Long productId) {
        return placeOrder(userId, productId);
    }

    /**
     * 下单主流程（骨架版本：<b>无事务、非原子扣减</b>）。
     *
     * <p>顺序：查商品 → 查是否重复下单 → 扣库存 → 建订单。</p>
     */
    public SeckillResult placeOrder(Long userId, Long productId) {
        Product product = productMapper.findById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != 1) {
            return SeckillResult.soldOut(productId);
        }
        if (orderMapper.countByUserAndProduct(userId, productId) > 0) {
            return SeckillResult.duplicate(userId, productId);
        }

        // ⚠️ 先读、再判断、最后写回：三步之间没有原子性保证（Stage 3 要改掉它）
        Integer stock = productMapper.selectStock(productId);
        if (stock == null || stock <= 0) {
            return SeckillResult.soldOut(productId);
        }
        productMapper.updateStock(productId, stock - 1);

        SeckillOrder order = new SeckillOrder();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setQuantity(1);
        order.setAmount(product.getPrice());
        order.setStatus(0);
        orderMapper.insertOrder(order);

        return SeckillResult.ok(order.getId(), productMapper.selectStock(productId));
    }

    /**
     * Stage 6：按"固定顺序"加分布式锁后再下单。
     *
     * <p>骨架里这里是 TODO。实现要点：先按 id 升序把所有分布式锁拿齐，再进事务下单，
     * 最后逆序释放。所有线程都按同一个全局顺序拿锁，就不可能形成死锁的循环等待。</p>
     */
    public SeckillResult seckillWithLockOrder(Long userId, List<Long> productIds) {
        throw new UnsupportedOperationException(
                "按序加锁尚未实现（Stage 6）：seckillWithLockOrder 需要按 id 升序拿齐分布式锁再下单");
    }

    /** 按商品汇总成交额（Stage 4 对账口径）。 */
    public BigDecimal totalAmount(Long productId) {
        return orderMapper.sumAmountByProduct(productId);
    }

    /** 按商品统计订单数（Stage 4 对账口径）。 */
    public int orderCount(Long productId) {
        return orderMapper.countByProduct(productId);
    }

    /** 分布式锁只在需要它的阶段（Stage 6）才有依赖，所以用可选注入，避免其它阶段被迫起 Redis。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLockService(LockService lockService) {
        this.lockService = lockService;
    }

    /** 内部用：把"待处理的商品 id 列表"排成全局统一的加锁顺序。 */
    protected List<Long> globalLockOrder(List<Long> productIds) {
        List<Long> ordered = new ArrayList<>(productIds);
        ordered.sort(Long::compareTo);
        return ordered;
    }
}
