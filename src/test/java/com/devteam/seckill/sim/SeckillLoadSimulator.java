package com.devteam.seckill.sim;

import com.devteam.seckill.mapper.OrderMapper;
import com.devteam.seckill.mapper.ProductMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
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
 * 并发秒杀请求模拟器（仿真真实前端高并发打 HTTP 接口）。
 *
 * <p>用 100 个线程 + CountDownLatch 起跑门，尽可能在同一瞬间向
 * {@code POST /seckill?userId=&productId=} 发起请求，用来复现 / 验证超卖现象。</p>
 *
 * <h2>约定指标：零超卖</h2>
 * <ul>
 *   <li>{@code 成功(200) + 售罄(410) + 重复(409) == 总线程数}（每个请求都有明确归宿）；</li>
 *   <li>{@code 成功数 <= 初始库存}（绝不多卖）；</li>
 *   <li>{@code 最终库存 == 初始库存 - 成功数}（扣减与订单一一对应）；</li>
 *   <li>{@code 落库订单数 == 成功数}。</li>
 * </ul>
 *
 * <p>Stage 2/3 未实现时接口返回 501，本模拟器会把 501 记为 notImplemented 并**如实失败**
 * ——它报的红就是"下单没实现"，不掩饰。</p>
 *
 * <p>运行（默认 Surefire 排除了 sim 分组，需要显式打开并清空排除项）：
 * <pre>
 *   mvn test -Dgroups=sim -Dtest.excludedGroups=
 * </pre>
 */
@Tag("sim")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeckillLoadSimulator {

    private static final int THREADS = 100;
    /** 专用 fixture 商品：库存 10（与 Stage 3 的"热点商品"同构）。 */
    private static final long PRODUCT_ID = 9_009_001L;
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private TestRestTemplate restTemplate;
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
                PRODUCT_ID, "sim-hot-item", 9009L, 1, new BigDecimal("1.00"), INITIAL_STOCK, 0);
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
    void fireConcurrentSeckillRequests() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger notImplemented = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<Integer> unexpected = new ArrayList<>();

        for (int i = 1; i <= THREADS; i++) {
            final long userId = 9_009_100L + i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    String url = "/seckill?userId=" + userId + "&productId=" + PRODUCT_ID;
                    ResponseEntity<String> resp = restTemplate.postForEntity(url, null, String.class);
                    int status = resp.getStatusCode().value();
                    if (status == 200) {
                        ok.incrementAndGet();
                    } else if (status == 410) {
                        soldOut.incrementAndGet();
                    } else if (status == 409) {
                        duplicate.incrementAndGet();
                    } else if (status == 501) {
                        notImplemented.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        synchronized (unexpected) {
                            unexpected.add(status);
                        }
                    }
                } catch (Throwable t) {
                    other.incrementAndGet();
                    synchronized (unexpected) {
                        unexpected.add(-1);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        // 所有线程就绪后同时放行，制造瞬时并发
        startGate.countDown();
        assertTrue(done.await(180, TimeUnit.SECONDS), "并发请求未在 180s 内结束");
        pool.shutdownNow();

        int finalStock = productMapper.selectStock(PRODUCT_ID);
        int orders = orderMapper.countByProduct(PRODUCT_ID);

        System.out.println("==== 并发秒杀模拟结果 ====");
        System.out.println("并发线程数        = " + THREADS);
        System.out.println("初始库存          = " + INITIAL_STOCK);
        System.out.println("成功下单 (200)    = " + ok.get());
        System.out.println("售罄 (410)        = " + soldOut.get());
        System.out.println("重复下单 (409)    = " + duplicate.get());
        System.out.println("未实现 (501)      = " + notImplemented.get());
        System.out.println("其它/异常         = " + other.get() + (unexpected.isEmpty() ? "" : " " + unexpected));
        System.out.println("最终库存          = " + finalStock);
        System.out.println("落库订单数        = " + orders);
        System.out.println("==========================");

        assertEquals(THREADS, ok.get() + soldOut.get() + duplicate.get() + notImplemented.get() + other.get(),
                "所有请求都应被统计到，总数应等于并发线程数");
        assertEquals(0, other.get(), "不应出现非预期状态码：" + unexpected);
        assertEquals(0, notImplemented.get(),
                "接口返回 501：秒杀下单还没实现（Stage 2/3 未完成）");

        // 零超卖：核心判据
        assertTrue(ok.get() <= INITIAL_STOCK,
                "成功下单数不能超过初始库存（超卖！）：成功=" + ok.get() + " 库存=" + INITIAL_STOCK);
        assertEquals(INITIAL_STOCK - ok.get(), finalStock,
                "最终库存必须等于 初始库存 - 成功数（扣减与订单要一一对应）");
        assertEquals(ok.get(), orders, "落库订单数必须等于成功下单数");
    }
}
