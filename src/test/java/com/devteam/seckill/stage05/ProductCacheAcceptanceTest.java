package com.devteam.seckill.stage05;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.mapper.ProductMapper;
import com.devteam.seckill.service.ProductCacheLoader;
import com.devteam.seckill.service.ProductCacheStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 5 验收测试：缓存三件套（穿透 / 击穿 / 雪崩）。
 *
 * <p>判据全部落在<b>可观测的事实</b>上，而不是实现细节：
 * <ul>
 *   <li>缓存命中时不再打 DB —— 用 {@link ProductCacheLoader#loadCount()} 数出来的；</li>
 *   <li>"空结果"确实被缓存了 —— 用 {@code peekRawCache} 看 Redis 里的原始值；</li>
 *   <li>并发未命中只打一次 DB —— 20 个线程抢同一个不存在的 id，{@code loadCount} 必须恰好 +1；</li>
 *   <li>失效后能重新加载 —— {@code evict} 之后 {@code loadCount} 再次 +1。</li>
 * </ul>
 *
 * <p>前置：Redis 必须已启动（{@code docker compose up -d}）。</p>
 * <p>运行：{@code mvn test -Dgroups=stage05}</p>
 */
@Tag("stage05")
@SpringBootTest
class ProductCacheAcceptanceTest {

    private static final long PRODUCT_ID = 9_005_001L;
    /** 这个 id 故意不存在：用来验缓存穿透与击穿。 */
    private static final long MISSING_ID = 9_005_999L;

    private static final int CONCURRENT_THREADS = 20;

    @Autowired
    private ProductCacheStore cacheStore;
    @Autowired
    private ProductCacheLoader cacheLoader;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        jdbcTemplate.update("INSERT INTO product (id, name, category_id, status, price, stock, version) "
                        + "VALUES (?,?,?,?,?,?,?)",
                PRODUCT_ID, "stage05-fixture", 9005L, 1, new BigDecimal("66.60"), 7, 0);
        cacheLoader.resetLoadCount();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductCacheStore.KEY_PREFIX + PRODUCT_ID);
        redisTemplate.delete(ProductCacheStore.KEY_PREFIX + MISSING_ID);
        cleanFixtures();
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
    }

    /** 判据 1：首次未命中打 DB 并写缓存，第二次命中缓存不再打 DB。 */
    @Test
    void secondQuery_hitsCache_andDoesNotTouchDatabase() {
        Product first = cacheStore.getProductCached(PRODUCT_ID);
        assertNotNull(first, "商品存在，首次查询应返回商品（骨架里这里会抛 UnsupportedOperationException）");
        assertEquals("stage05-fixture", first.getName());
        long afterFirst = cacheLoader.loadCount();
        assertEquals(1, afterFirst, "首次未命中应当恰好加载 1 次");

        Product second = cacheStore.getProductCached(PRODUCT_ID);
        assertNotNull(second);
        assertEquals("stage05-fixture", second.getName());
        assertEquals(afterFirst, cacheLoader.loadCount(),
                "第二次查询应命中缓存、不再打 DB（loadCount 不该增长）");

        Object raw = cacheStore.peekRawCache(PRODUCT_ID);
        assertNotNull(raw, "缓存里应当有值（key=" + ProductCacheStore.KEY_PREFIX + PRODUCT_ID + "）");
    }

    /** 判据 2：<b>防穿透</b> —— 不存在的 id 也写空值缓存，重复查询仍不打 DB。 */
    @Test
    void missingProduct_isCachedAsNullSentinel_andStopsPenetration() {
        Product miss1 = cacheStore.getProductCached(MISSING_ID);
        assertNull(miss1, "商品不存在，应当返回 null");
        assertEquals(1, cacheLoader.loadCount(), "首次查不存在的 id，应当加载 1 次（用于确认它真的不存在）");

        Object raw = cacheStore.peekRawCache(MISSING_ID);
        assertNotNull(raw, "不存在的 id 也必须留下缓存痕迹（空值哨兵），否则缓存穿透挡不住");

        Product miss2 = cacheStore.getProductCached(MISSING_ID);
        assertNull(miss2, "第二次查询仍应返回 null");
        assertEquals(1, cacheLoader.loadCount(),
                "第二次查同一个不存在的 id 不该再打 DB —— 空值缓存没生效（缓存穿透）");
    }

    /**
     * 判据 3：<b>防击穿</b> —— 20 个并发线程查同一个不存在的 id，DB 只被加载 1 次。
     *
     * <p>骨架（直查、无单飞协调）在这里会得到 loadCount == 20。</p>
     */
    @Test
    void concurrentMisses_loadDatabaseOnlyOnce() throws InterruptedException {
        cacheLoader.resetLoadCount();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Product> results = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(cacheStore.getProductCached(MISSING_ID));
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "并发查询未在 120s 内结束");
        pool.shutdownNow();

        if (failure.get() != null) {
            throw new AssertionError("并发查询出现异常：" + failure.get(), failure.get());
        }
        System.out.println("==== Stage5 防击穿 ====");
        System.out.println("并发线程数   = " + CONCURRENT_THREADS);
        System.out.println("DB 加载次数  = " + cacheLoader.loadCount());
        System.out.println("========================");

        assertEquals(1, cacheLoader.loadCount(),
                "并发未命中时 DB 只应被加载 1 次（单飞/互斥锁 + 双重检查）；"
                        + "实际=" + cacheLoader.loadCount() + "，说明缓存击穿没防住");
        assertEquals(CONCURRENT_THREADS, results.size(), "每个线程都应拿到结果");
        assertTrue(results.stream().allMatch(java.util.Objects::isNull),
                "不存在的商品，所有线程都应拿到 null");
    }

    /** 判据 4：缓存失效后下一次查询重新加载（缓存与 DB 的一致性入口）。 */
    @Test
    void evict_forcesReloadOnNextQuery() {
        assertNotNull(cacheStore.getProductCached(PRODUCT_ID));
        assertEquals(1, cacheLoader.loadCount());

        cacheStore.evictProductCache(PRODUCT_ID);
        assertNull(cacheStore.peekRawCache(PRODUCT_ID), "evict 之后缓存里不该还有值");

        assertNotNull(cacheStore.getProductCached(PRODUCT_ID));
        assertEquals(2, cacheLoader.loadCount(), "失效后下一次查询必须重新加载");
    }

    /** 判据 5：<b>防雪崩</b> —— TTL 必须带随机抖动，不能是一根定值。 */
    @Test
    void ttlIsJittered_toAvoidSynchronizedExpiry() {
        java.util.Set<Object> ttlSamples = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            long jittered = cacheStore.ttlWithJitterForTest();
            assertTrue(jittered >= ProductCacheStore.BASE_TTL_SECONDS
                            && jittered <= ProductCacheStore.BASE_TTL_SECONDS + ProductCacheStore.TTL_JITTER_SECONDS,
                    "TTL 应落在 [基准, 基准+抖动] 区间内，实际=" + jittered);
            ttlSamples.add(jittered);
        }
        assertTrue(ttlSamples.size() > 1,
                "12 次采样只得到 1 个 TTL 值（" + ttlSamples + "）：TTL 没有抖动，"
                        + "一批 key 会在同一秒集体失效 → 缓存雪崩");
    }

    /** 旁证：缓存里存的确实是能反序列化的商品对象（不是字符串化后的不可用值）。 */
    @Test
    void cachedProduct_isDeserializable() {
        cacheStore.getProductCached(PRODUCT_ID);
        Product direct = productMapper.findById(PRODUCT_ID);
        assertNotNull(direct);
        Object raw = cacheStore.peekRawCache(PRODUCT_ID);
        assertNotNull(raw);
        Product cached = cacheStore.getProductCached(PRODUCT_ID);
        assertEquals(direct.getPrice().compareTo(cached.getPrice()), 0, "缓存反序列化后的价格应与 DB 一致");
    }
}
