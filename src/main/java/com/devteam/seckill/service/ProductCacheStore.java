package com.devteam.seckill.service;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品缓存（Stage 5 缓存三件套的落点）。
 *
 * <h2>骨架现状</h2>
 * <p>{@link #getProductCached(Long)} / {@link #evictProductCache(Long)} 都是 TODO 桩，
 * 调用抛 {@code UnsupportedOperationException}；{@link ProductCacheLoader} 也只是"直查 DB +
 * 每次加载后写一次缓存"的<b>没有并发协调</b>版本。所以 Stage 5 的验收测试初始为红。</p>
 *
 * <h2>你要实现的四件事</h2>
 * <ol>
 *   <li><b>缓存读写</b>：命中直接返回，未命中加载后写回并设置 TTL；</li>
 *   <li><b>防穿透</b>：查不到的 id 也要缓存"空结果"（{@link #NULL_SENTINEL} 哨兵），且 TTL 要短；</li>
 *   <li><b>防击穿</b>：并发未命中时只允许一次 DB 加载 —— 注意 {@link ProductCacheLoader}
 *       已经给了你 {@code cachePeek}（再看一眼缓存）和 {@code cacheWrite}（写回缓存）
 *       两个回调的位置，<b>用它们把"查 DB + 写缓存"放进同一个临界区</b>；</li>
 *   <li><b>防雪崩</b>：TTL 加随机抖动（{@link #ttlWithJitter()} 已备好）。</li>
 * </ol>
 *
 * <p>本类刻意与 {@link ProductService}（纯查询/搜索，Stage 0/1 已交付）分开：
 * 缓存是另一个关注点，混在一起会让 Stage 0/1 的冒烟测试和 Stage 2/3/4 的用例都被迫依赖 Redis。</p>
 */
@Service
public class ProductCacheStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProductCacheStore.class);

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
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    public ProductCacheStore(ProductMapper productMapper,
                             ProductCacheLoader cacheLoader,
                             org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate) {
        this.productMapper = productMapper;
        this.cacheLoader = cacheLoader;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 带缓存的商品查询。
     *
     * <p>骨架里这里是 TODO。目标语义：</p>
     * <ol>
     *   <li>先查 Redis：命中商品对象直接返回；命中 {@link #NULL_SENTINEL} 返回 {@code null}；</li>
     *   <li>未命中：调用 {@code cacheLoader.loadWithDoubleCheck(id, cachePeek, cacheWrite, fallback)}，
     *       其中 cachePeek 要能把"空值哨兵"也当成一次命中（否则负缓存挡不住并发穿透），
     *       cacheWrite 负责在临界区里写回缓存；</li>
     *   <li>返回结果（不存在时返回 {@code null}，<b>并且要把"不存在"也缓存下来</b>）。</li>
     * </ol>
     */
    public Product getProductCached(Long id) {
        throw new UnsupportedOperationException(
                "商品缓存尚未实现（Stage 5）：getProductCached 需要先查 Redis，未命中再单飞加载并写回（含空值哨兵）");
    }

    /** 删除某个商品的缓存（商品变更后调用，保证缓存与 DB 的最终一致）。 */
    public void evictProductCache(Long id) {
        throw new UnsupportedOperationException(
                "商品缓存尚未实现（Stage 5）：evictProductCache 需要删除对应的缓存 key");
    }

    /** 读缓存原始值（仅供测试与排查观察：可能是商品对象、也可能是空值哨兵）。 */
    public Object peekRawCache(Long id) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + id);
    }

    /**
     * 供 {@link ProductCacheLoader} 在临界区里"再看一眼缓存"。
     *
     * <p>骨架里这里是 TODO。提示：返回值是 {@link CacheHit}，它比 {@code Product} 多一个
     * {@code hit} 标志——用来区分"缓存里什么都没有"（要查 DB）与"缓存里存的是空值哨兵"
     * （确定不存在，<b>不要再查 DB</b>）。少了这个区分，20 个并发线程会各查一次 DB。</p>
     */
    private CacheHit peekForDoubleCheck(Long id) {
        throw new UnsupportedOperationException(
                "商品缓存尚未实现（Stage 5）：peekForDoubleCheck 需要区分「没缓存」与「缓存了空值」");
    }

    /** 基准 TTL 之上加 0~{@link #TTL_JITTER_SECONDS} 秒随机抖动（防雪崩）。 */
    protected long ttlWithJitter() {
        return BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(TTL_JITTER_SECONDS + 1);
    }

    /** 供验收测试采样 TTL 抖动（判据：多次采样不能只有一个值，否则会缓存雪崩）。 */
    public long ttlWithJitterForTest() {
        return ttlWithJitter();
    }
}
