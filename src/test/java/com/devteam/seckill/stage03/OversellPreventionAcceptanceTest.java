package com.devteam.seckill.stage03;

import com.devteam.seckill.domain.dto.SeckillResult;
import com.devteam.seckill.mapper.OrderMapper;
import com.devteam.seckill.mapper.ProductMapper;
import com.devteam.seckill.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3 验收测试：并发下<b>零超卖</b>。
 *
 * <p>场景：库存恰好 10 的热点商品，30 个并发线程（每线程一个不同用户）同时下单。判据是三个
 * 「守恒」：
 * <ol>
 *   <li>{@code 成功数 == 初始库存}（10）——不多不少；</li>
 *   <li>{@code 最终库存 == 0}——既不能超卖成负数，也不能少卖（少卖说明锁粒度/重试策略把正常请求也拒了）；</li>
 *   <li>{@code 订单数 == 10}——落库的订单必须与实际扣减一一对应。</li>
 * </ol>
 *
 * <p>骨架红的原因：{@code seckill()} 是"先查库存 → 应用里判断 → 再 UPDATE"，
 * 多个线程可以在各自的读里都看到 stock=10，然后各扣一次（丢失更新）——
 * 典型表现是"成功数 &gt; 10、库存最终小于 0"，或者成功数对但库存对不上。</p>
 *
 * <p>运行：{@code mvn test -Dgroups=stage03}（并发真打 DB，比普通用例慢几秒）。</p>
 */
@Tag("stage03")
@SpringBootTest
class OversellPreventionAcceptanceTest {

    private static final long PRODUCT_ID = 9_003_001L;
    private static final int INITIAL_STOCK = 10;
    private static final int THREADS = 30;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        jdbcTemplate.update("INSERT INTO product (id, name, category_id, status, price, stock, version) "
                        + "VALUES (?,?,?,?,?,?,?)",
                PRODUCT_ID, "stage03-hot-item", 9003L, 1, new BigDecimal("9.90"), INITIAL_STOCK, 0);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM seckill_order WHERE product_id = ?", PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
    }

    @Test
    void concurrentSeckill_neverOversells() throws InterruptedException {
        // 预热：让 HikariCP 把连接建好、MyBatis 把语句解析好，
        // 否则前几秒的线程会把"建连开销"也算进并发窗口，掩盖竞态。
        for (int i = 0; i < 3; i++) {
            try {
                seckillService.seckill(9_003_900L + i, PRODUCT_ID);
            } catch (Exception ignored) {
                // 预热阶段的异常不影响判据
            }
        }
        jdbcTemplate.update("DELETE FROM seckill_order WHERE product_id = ?", PRODUCT_ID);
        jdbcTemplate.update("UPDATE product SET stock = ? WHERE id = ?", INITIAL_STOCK, PRODUCT_ID);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            final long userId = 9_003_100L + i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    SeckillResult r = seckillService.seckill(userId, PRODUCT_ID);
                    if (r.success()) {
                        success.incrementAndGet();
                    } else if (String.valueOf(r.reason()).startsWith("SOLD_OUT")) {
                        soldOut.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "并发下单未在 120s 内结束");
        pool.shutdownNow();

        int finalStock = productMapper.selectStock(PRODUCT_ID);
        int orders = orderMapper.countByProduct(PRODUCT_ID);

        System.out.println("==== Stage3 并发下单结果 ====");
        System.out.println("初始库存        = " + INITIAL_STOCK);
        System.out.println("并发线程数      = " + THREADS);
        System.out.println("成功下单        = " + success.get());
        System.out.println("售罄拒绝        = " + soldOut.get());
        System.out.println("其它失败        = " + other.get());
        System.out.println("最终库存        = " + finalStock);
        System.out.println("落库订单数      = " + orders);
        System.out.println("============================");

        if (!unexpected.isEmpty()) {
            throw new AssertionError("并发过程中出现非预期异常（例如死锁/锁等待超时）：" + unexpected.get(0), unexpected.get(0));
        }
        assertEquals(0, other.get(), "除成功与售罄外不应有其它结果（其它=" + other.get() + "）");
        assertEquals(INITIAL_STOCK, success.get(),
                "成功下单数必须恰好等于初始库存：多了=超卖，少了=把正常请求也拒了");
        assertEquals(0, finalStock, "最终库存必须恰好为 0（负数=超卖，正数=漏卖）");
        assertEquals(INITIAL_STOCK, orders, "落库订单数必须与扣减量一致（不能扣了库存却没订单）");
    }
}
