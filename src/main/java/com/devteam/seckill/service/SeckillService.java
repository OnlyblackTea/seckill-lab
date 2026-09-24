package com.devteam.seckill.service;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.domain.SeckillOrder;
import com.devteam.seckill.domain.dto.SeckillResult;
import com.devteam.seckill.mapper.OrderMapper;
import com.devteam.seckill.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀下单服务（Stage 2 事务 / Stage 3 防超卖 / Stage 6 按序加锁的参考解）。
 *
 * <h2>Stage 2：为什么事务边界在 service 层</h2>
 * <p>"扣库存 + 建订单"是**一个业务操作**，必须同生共死（ACID 的原子性）。事务边界只能画在
 * service 层：controller 加事务会把 HTTP 往返也算进事务、拉长持锁时间；mapper 太细，
 * 一个 mapper 方法一个事务就退化成两个独立事务（正是骨架的 bug 形态）。</p>
 *
 * <h2>Stage 3：为什么这样写就不会超卖</h2>
 * <p>把"判断够不够"和"扣减"合并进<b>同一条 UPDATE 的 WHERE</b>：
 * {@code UPDATE product SET stock = stock - 1 WHERE id = ? AND stock > 0}。
 * InnoDB 执行 UPDATE 时对该行加排他锁（X 锁），并基于<b>最新已提交版本</b>重新求值 WHERE——
 * 也就是"当前读"。所以并发的第二个事务要么被阻塞到第一个提交后重新判断（发现 stock=0 → 影响 0 行 →
 * 售罄），要么因行锁竞争串行化，<b>不可能两个事务都读到旧库存后各扣一次</b>。</p>
 *
 * <p>对比骨架的写法（先 selectStock 读到 10 → 应用里判断 → updateStock 写回 9）：读与写之间隔着
 * 一个应用层判断，两个线程可以都读到 10、各写回 9，最终卖出 11 件而库存还剩 9 —— 丢失更新。</p>
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    /** 唯一键冲突（并发重复下单）时的重试上限。 */
    private static final int MAX_ATTEMPTS = 200;

    /** 分布式锁的租约（TTL）：必须明显大于一次下单的最长耗时，否则锁会先过期、互斥失效。 */
    private static final long LOCK_TTL_MILLIS = 30_000L;

    /** 抢不到锁时的最长等待时间。 */
    private static final long LOCK_WAIT_MILLIS = 10_000L;

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private LockService lockService;

    public SeckillService(ProductMapper productMapper, OrderMapper orderMapper) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 秒杀下单（Stage 2 + Stage 3 的合流入场）。
     *
     * <p>整个流程在一个事务里：先原子扣库存，再建订单；任何一步失败都整体回滚。
     * 售罄 / 重复下单用返回值表达（便于并发统计），唯一键冲突则重试一次。</p>
     */
    public SeckillResult seckill(Long userId, Long productId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return placeOrder(userId, productId);
            } catch (DuplicateKeyException e) {
                // uk_user_product 被并发插入撞上：这次没抢到，重试一次即可
                log.debug("并发重复下单，重试 attempt={} user={} product={}", attempt, userId, productId);
            }
        }
        return SeckillResult.duplicate(userId, productId);
    }

    /**
     * 事务边界：{@code @Transactional} 让"扣库存 + 建订单"原子提交 / 回滚。
     *
     * <p>{@code rollbackFor = Exception.class} 是显式声明：Spring 默认只对
     * RuntimeException / Error 回滚，写出来可以避免"换成受检异常后突然不回滚"的坑。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillResult placeOrder(Long userId, Long productId) {
        // 1) 商品必须存在且上架
        Product product = productMapper.findById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != 1) {
            return SeckillResult.soldOut(productId);
        }
        // 2) 同一用户对同一商品只允许一单（表上 uk_user_product 唯一键兜底）
        if (orderMapper.countByUserAndProduct(userId, productId) > 0) {
            return SeckillResult.duplicate(userId, productId);
        }
        // 3) 原子扣减：判断与扣减在同一条 UPDATE 里（当前读 + 行锁），影响 0 行 = 售罄
        int affected = productMapper.decrementStock(productId);
        if (affected == 0) {
            return SeckillResult.soldOut(productId);
        }
        // 4) 建订单（同一事务；此处失败会让第 3 步一起回滚）
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
     * Stage 6：按"固定顺序"加分布式锁后再下单，破坏死锁的循环等待条件。
     *
     * <p>死锁的四个必要条件里，工程上最容易破坏的是<b>循环等待</b>：只要所有事务都按同一个
     * 全局顺序（这里按 id 升序）去拿锁，就不可能形成环。而"每个线程按自己传入的顺序加锁"
     * 正是线上死锁的成因——两个线程分别按 (1,2) 与 (2,1) 加锁，各持一把、互等一把。</p>
     *
     * <p>本方法演示的是<b>跨进程</b>那一层：先按 id 升序把所有分布式锁拿齐，再进事务下单，
     * 最后逆序释放。这样即使两个实例同时打进来，锁的获取顺序也是一致的。</p>
     */
    public SeckillResult seckillWithLockOrder(Long userId, List<Long> productIds) {
        List<Long> ordered = globalLockOrder(productIds);
        List<LockToken> held = new ArrayList<>();
        try {
            for (Long id : ordered) {
                LockToken token = LockToken.of("lock:seckill:" + id, Thread.currentThread().getName());
                if (!tryLockWaiting(token, LOCK_TTL_MILLIS, LOCK_WAIT_MILLIS)) {
                    throw new LockAcquireException(token.key(), LOCK_WAIT_MILLIS);
                }
                held.add(token);
            }
            return seckill(userId, ordered.get(0));
        } finally {
            // 逆序释放，缩短与其它线程的锁竞争窗口
            for (int i = held.size() - 1; i >= 0; i--) {
                LockToken token = held.get(i);
                lockService.unlock(token.key(), token.token());
            }
        }
    }

    /**
     * 排队等锁：{@code tryLock} 本身是非阻塞的（抢不到立刻返回 false），
     * 但业务上"抢不到就失败"通常是错的——正常竞争应该短暂等待。
     *
     * <p>注意 TTL 必须明显大于"一次下单的最长耗时"，否则会出现：A 还在事务里没提交，
     * 锁已经过期，B 拿到锁同时扣同一行库存 → 互斥失效。租约（TTL）与应用耗时的关系
     * 是分布式锁最容易被忽略的坑。</p>
     */
    private boolean tryLockWaiting(LockToken token, long ttlMillis, long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;
        do {
            if (lockService.tryLock(token.key(), token.token(), ttlMillis)) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
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

    /** 把"待处理的商品 id 列表"排成全局统一的加锁顺序。 */
    protected List<Long> globalLockOrder(List<Long> productIds) {
        List<Long> ordered = new ArrayList<>(productIds);
        ordered.sort(Long::compareTo);
        return ordered;
    }
}
