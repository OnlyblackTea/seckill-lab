package com.devteam.seckill.stage06;

import com.devteam.seckill.domain.dto.SeckillResult;
import com.devteam.seckill.mapper.OrderMapper;
import com.devteam.seckill.mapper.ProductMapper;
import com.devteam.seckill.service.LockService;
import com.devteam.seckill.service.LockToken;
import com.devteam.seckill.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6 验收测试（选修）：Redis 分布式锁 + 死锁规避。
 *
 * <p>五组判据：
 * <ol>
 *   <li><b>互斥</b>：同一个 key 第二次 tryLock 必须失败；</li>
 *   <li><b>释放后可重抢</b>：unlock 之后能再拿到；</li>
 *   <li><b>TTL 兜底</b>：锁到期自动释放，不会永久死锁；</li>
 *   <li><b>误删防护</b>：A 的锁过期后被 B 抢走，A 再 unlock <b>不能</b>删掉 B 的锁；</li>
 *   <li><b>无死锁</b>：两个线程以相反顺序对同两个商品加锁下单，必须全部成功、无死锁异常。</li>
 * </ol>
 *
 * <p>骨架红的原因：{@code LockServiceImpl} 三个方法都抛 {@code UnsupportedOperationException}。</p>
 * <p>运行：{@code mvn test -Dgroups=stage06}</p>
 */
@Tag("stage06")
@SpringBootTest
class DistributedLockAcceptanceTest {

    private static final long PRODUCT_LOW = 9_006_001L;
    private static final long PRODUCT_HIGH = 9_006_002L;

    @Autowired
    private LockService lockService;
    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        // 库存 = 线程数：8 个线程各买 1 件，必须全部成功（这样"有没有失败"才是干净的死锁信号）
        jdbcTemplate.update("INSERT INTO product (id, name, category_id, status, price, stock, version) "
                        + "VALUES (?,?,?,?,?,?,?)",
                PRODUCT_LOW, "stage06-low", 9006L, 1, new BigDecimal("10.00"), 8, 0);
        jdbcTemplate.update("INSERT INTO product (id, name, category_id, status, price, stock, version) "
                        + "VALUES (?,?,?,?,?,?,?)",
                PRODUCT_HIGH, "stage06-high", 9006L, 1, new BigDecimal("20.00"), 8, 0);
    }

    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete("lock:seckill:" + PRODUCT_LOW);
        stringRedisTemplate.delete("lock:seckill:" + PRODUCT_HIGH);
        cleanFixtures();
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM seckill_order WHERE product_id IN (?,?)", PRODUCT_LOW, PRODUCT_HIGH);
        jdbcTemplate.update("DELETE FROM product WHERE id IN (?,?)", PRODUCT_LOW, PRODUCT_HIGH);
    }

    private String lockKey(long productId) {
        return "lock:seckill:" + productId;
    }

    /** 判据 1+2：互斥，且释放后可以重新获取。 */
    @Test
    void tryLock_isMutuallyExclusive_andReleasable() {
        String key = lockKey(PRODUCT_LOW);
        LockToken a = LockToken.of(key, "thread-A");

        assertTrue(lockService.tryLock(key, a.token(), 5_000L), "第一次抢锁应当成功");
        assertTrue(lockService.isHeldBy(key, a.token()), "抢到之后 isHeldBy 应为 true");

        String otherToken = LockToken.of(key, "thread-B").token();
        assertFalse(lockService.tryLock(key, otherToken, 5_000L),
                "锁已被 A 持有，B 再抢必须失败（互斥）");
        assertFalse(lockService.isHeldBy(key, otherToken), "B 不该被认为是持有者");

        lockService.unlock(key, a.token());
        assertFalse(lockService.isHeldBy(key, a.token()), "释放后 A 不再是持有者");
        assertTrue(lockService.tryLock(key, otherToken, 5_000L), "释放之后 B 应当能抢到");
        lockService.unlock(key, otherToken);
    }

    /** 判据 3：TTL 到期自动释放（防"持锁进程挂了导致永久死锁"）。 */
    @Test
    void lock_expiresAutomatically_afterTtl() throws InterruptedException {
        String key = lockKey(PRODUCT_LOW);
        String tokenA = LockToken.of(key, "thread-A").token();
        assertTrue(lockService.tryLock(key, tokenA, 400L));

        String tokenB = LockToken.of(key, "thread-B").token();
        assertFalse(lockService.tryLock(key, tokenB, 5_000L), "未到期前 B 抢不到");

        Thread.sleep(1_200L);

        assertFalse(lockService.isHeldBy(key, tokenA), "TTL 到期后 A 的锁应当自动消失");
        assertTrue(lockService.tryLock(key, tokenB, 5_000L),
                "TTL 到期后 B 必须能抢到 —— 否则持锁进程崩溃就会留下永久死锁");
        lockService.unlock(key, tokenB);
    }

    /**
     * 判据 4：<b>误删防护</b>。
     *
     * <p>时间线：A 抢锁（TTL 极短）→ A "停顿"到锁过期 → B 抢到同一把锁 →
     * A 醒来调 {@code unlock(自己的旧 token)}。<b>正确实现下这必须什么都不做</b>，
     * 因为 A 已经不是持有者；若实现是"按 key 直接 DEL"，B 的锁会被误删，互斥当场失效。</p>
     */
    @Test
    void unlock_withStaleToken_doesNotDeleteOtherOwnersLock() throws InterruptedException {
        String key = lockKey(PRODUCT_HIGH);
        String staleToken = LockToken.of(key, "slow-thread-A").token();
        assertTrue(lockService.tryLock(key, staleToken, 300L), "A 先抢到锁");

        Thread.sleep(900L); // A 停顿，锁过期

        String freshToken = LockToken.of(key, "thread-B").token();
        assertTrue(lockService.tryLock(key, freshToken, 5_000L), "锁过期后 B 应当能抢到");

        // A 醒来，用已经过期的 token 去 unlock —— 这是"误删"的高危时刻
        lockService.unlock(key, staleToken);

        assertTrue(lockService.isHeldBy(key, freshToken),
                "A 用过期 token 调 unlock 不能删掉 B 的锁（必须用 Lua 做「比对 token + 删除」的原子操作）");
        assertFalse(lockService.tryLock(key, LockToken.of(key, "thread-C").token(), 1_000L),
                "B 仍持有锁，C 不该抢到");

        lockService.unlock(key, freshToken);
        assertFalse(lockService.isHeldBy(key, freshToken), "持有者自己释放应当成功");
    }

    /**
     * 判据 5：<b>无死锁</b> —— 相反加锁顺序下的并发下单必须全部成功。
     *
     * <p>死锁四个必要条件里的"循环等待"是最容易破坏的一环：只要所有线程都按 id 升序
     * 拿锁，(低,高) 与 (高,低) 两种调用顺序都会走同一条加锁路径，不可能形成环。</p>
     */
    @Test
    void reversedLockOrder_doesNotDeadlock() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Long> allUsers = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final long userId = 9_006_100L + i;
            final boolean ascending = (i % 2 == 0);
            pool.submit(() -> {
                try {
                    startGate.await();
                    List<Long> order = ascending
                            ? List.of(PRODUCT_LOW, PRODUCT_HIGH)
                            : List.of(PRODUCT_HIGH, PRODUCT_LOW);
                    SeckillResult r = seckillService.seckillWithLockOrder(userId, order);
                    if (r.success()) {
                        success.incrementAndGet();
                        allUsers.add(userId);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(180, TimeUnit.SECONDS), "并发下单未在 180s 内结束（可能卡在锁等待）");
        pool.shutdownNow();

        if (failure.get() != null) {
            throw new AssertionError("出现异常（死锁？）：" + failure.get(), failure.get());
        }
        System.out.println("==== Stage6 按序加锁 ====");
        System.out.println("线程数              = " + threads);
        System.out.println("成功下单            = " + success.get());
        System.out.println("=================================");

        assertEquals(threads, success.get(), "所有线程都应成功下单（按 id 升序拿锁不该有失败/死锁）");
        // 语义说明：seckillWithLockOrder 会按 id 升序把【两个】商品的锁都拿齐（这正是"固定加锁顺序"
        // 的体现——(低,高) 与 (高,低) 两种调用都走同一条路径），然后下单列表里的第一个商品。
        // 所以只有 PRODUCT_LOW 会被扣减；PRODUCT_HIGH 的库存应原封不动（若它变了，说明锁顺序或下单目标错了）。
        assertEquals(0, productMapper.selectStock(PRODUCT_LOW),
                "低 id 商品应当被 8 个线程各扣 1 件，库存从 8 到 0");
        assertEquals(8, productMapper.selectStock(PRODUCT_HIGH),
                "高 id 商品只被加锁、不该被扣减，库存应仍为 8");
        assertEquals(threads, orderMapper.countByProduct(PRODUCT_LOW), "订单数必须与扣减量一致");
        assertEquals(0, orderMapper.countByProduct(PRODUCT_HIGH), "高 id 商品不该产生订单");
        assertTrue(productMapper.selectStock(PRODUCT_LOW) >= 0, "库存不能为负");
        assertTrue(productMapper.selectStock(PRODUCT_HIGH) >= 0, "库存不能为负");
    }
}
