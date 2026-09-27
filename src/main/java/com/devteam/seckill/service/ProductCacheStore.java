package com.devteam.seckill.service;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 商品缓存（Stage 5 缓存三件套的参考解）。
 *
 * <h2>读路径</h2>
 * <ol>
 *   <li>查 Redis：命中商品对象 → 直接返回；命中空值哨兵 → 返回 {@code null}；</li>
 *   <li>未命中 → 交给 {@link ProductCacheLoader} 做<b>单飞加载</b>（防击穿）；</li>
 *   <li>"查 DB + 写回缓存"都在 loader 的临界区里完成，见下面 {@code cacheWrite} 的说明。</li>
 * </ol>
 *
 * <h2>三个坑（真机实测踩过）</h2>
 * <ul>
 *   <li><b>写缓存必须和查 DB 同处临界区</b>。否则等待线程醒来时缓存仍是空的，又各查一次 DB
 *       —— 实测 DB 加载次数只能从 20 降到 4~6，等于没防住。</li>
 *   <li><b>空值哨兵要算"命中"</b>。否则负缓存只挡住"下一次请求"，挡不住"同一瞬间的并发请求"。</li>
 *   <li><b>TTL 要加抖动</b>。固定 TTL 会让一批 key 在同一秒集体失效 → 缓存雪崩。</li>
 * </ul>
 */
@Service
public class ProductCacheStore {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheStore.class);

    /** 缓存 key 前缀。 */
    public static final String KEY_PREFIX = "product:";

    /** 空值哨兵：表示"这个 id 在 DB 里确定不存在"，用来防缓存穿透。 */
    public static final String NULL_SENTINEL = "NULL";

    /** 正常商品缓存基准 TTL（秒）。 */
    public static final long BASE_TTL_SECONDS = 300L;

    /** TTL 随机抖动上限（秒）：打散过期时间，防缓存雪崩。 */
    public static final long TTL_JITTER_SECONDS = 60L;

    /** 空值缓存 TTL（秒）：比正常值短，兼顾"挡住穿透"与"数据补上后能较快恢复"。 */
    public static final long NULL_TTL_SECONDS = 30L;

    private final ProductMapper productMapper;
    private final ProductCacheLoader cacheLoader;
    private final RedisTemplate<String, Object> redisTemplate;

    public ProductCacheStore(ProductMapper productMapper,
                             ProductCacheLoader cacheLoader,
                             RedisTemplate<String, Object> redisTemplate) {
        this.productMapper = productMapper;
        this.cacheLoader = cacheLoader;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 带缓存的商品查询。
     *
     * @return 商品；不存在时返回 {@code null}（<b>并且会把"不存在"也缓存下来</b>）
     */
    public Product getProductCached(Long id) {
        String key = KEY_PREFIX + id;

        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            if (NULL_SENTINEL.equals(cached)) {
                return null;   // 空值哨兵：挡住"用不存在的 id 反复穿透"
            }
            if (cached instanceof Product product) {
                return product;
            }
            log.warn("缓存值类型异常，按未命中处理：key={} type={}", key, cached.getClass().getName());
        }

        // 未命中：单飞加载。
        // （2026-09-24 真机回归发现）最初这里写成了"锁里查 DB、锁外写缓存"这种很自然的分工，
        // 结果 20 个并发线程仍然加载了 4~6 次：等待线程醒来时缓存还是空的，于是它也去查一次。
        // 所以 cacheWrite 也必须在 loader 的临界区里执行。
        CacheHit loaded = cacheLoader.loadWithDoubleCheck(
                id,
                this::peekForDoubleCheck,
                product -> writeCache(key, product),
                () -> productMapper.selectById(id));

        return loaded.product();
    }

    /** 把加载结果写回缓存：商品用抖动 TTL，null 用更短的空值 TTL（防穿透）。 */
    private void writeCache(String key, Product product) {
        if (product == null) {
            redisTemplate.opsForValue().set(key, NULL_SENTINEL, NULL_TTL_SECONDS, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().set(key, product, ttlWithJitter(), TimeUnit.SECONDS);
        }
    }

    /** 删除某个商品的缓存（商品变更后调用，保证缓存与 DB 的最终一致）。 */
    public void evictProductCache(Long id) {
        redisTemplate.delete(KEY_PREFIX + id);
    }

    /** 读缓存原始值（仅供测试与排查观察：可能是商品对象、也可能是空值哨兵）。 */
    public Object peekRawCache(Long id) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + id);
    }

    /**
     * 供 {@link ProductCacheLoader} 在临界区里"再看一眼缓存"。
     *
     * <p>关键：必须把<b>空值哨兵</b>也报成一次有效命中（{@code CacheHit.cached(null)}）。
     * 如果这里对哨兵返回 {@code null}（表示"没缓存"），等待线程就会以为自己没命中、
     * 又去查一次 DB —— 负缓存只挡住"下一次请求"，挡不住"同一瞬间的并发请求"。</p>
     */
    private CacheHit peekForDoubleCheck(Long id) {
        Object cached = redisTemplate.opsForValue().get(KEY_PREFIX + id);
        if (cached == null) {
            return null;                       // 真的没缓存
        }
        if (NULL_SENTINEL.equals(cached)) {
            return CacheHit.cached(null);      // 负缓存命中：确定不存在，不必再查 DB
        }
        return CacheHit.cached((Product) cached);
    }

    /** 基准 TTL 之上加 0~{@link #TTL_JITTER_SECONDS} 秒随机抖动（防雪崩）。 */
    protected long ttlWithJitter() {
        return BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(TTL_JITTER_SECONDS + 1);
    }

    /**
     * 供验收测试采样 TTL 抖动（判据：多次采样不能只有一个值，否则会缓存雪崩）。
     *
     * <p>它不是"为了让测试通过"的钩子，而是把"TTL 到底是不是随机的"这一事实暴露出来——
     * 否则测试只能去读 Redis 的 TTL，而 300s 量级的抖动在秒级粒度上根本观察不到。</p>
     */
    public long ttlWithJitterForTest() {
        return ttlWithJitter();
    }
}
