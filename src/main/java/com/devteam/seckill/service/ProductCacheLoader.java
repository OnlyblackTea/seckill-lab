package com.devteam.seckill.service;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 商品「缓存未命中时的加载器」（Stage 5 防击穿的战场）。
 *
 * <p>为什么要把"加载"单独抽出来：缓存击穿（cache stampede）的本质是
 * <b>同一个热点 key 失效的瞬间，N 个并发请求同时去重建缓存</b>。
 * 要防住它，就必须有地方能"让其中一个线程去重建、其余线程等它"——那就是本类。</p>
 *
 * <h2>骨架现状</h2>
 * <p>{@link #loadWithDoubleCheck} 目前<b>既不查缓存也不写缓存</b>，只是"每次都执行 fallback"，
 * 也就是完全没有并发协调。{@link #loadCount()} 会如实反映"真实打了几次 DB"，
 * 验收测试据此断言防击穿有没有生效（骨架下 20 个并发线程 → 20 次加载）。</p>
 *
 * <h2>Stage 5 你要做的事</h2>
 * <p>把 {@code loadWithDoubleCheck} 改成真正的单飞：</p>
 * <ol>
 *   <li>临界区里先用 {@code cachePeek} 再看一眼缓存（双重检查）——命中就直接复用；</li>
 *   <li>缓存仍没有才执行 {@code fallback} 查 DB（此时才 {@code loads.increment()}）；</li>
 *   <li><b>紧接着在同一个临界区里</b>用 {@code cacheWrite} 把结果写回缓存
 *       （放在锁外是常见错解：等待线程醒来时缓存还空着，于是又各查一次 DB）；</li>
 *   <li>锁对象 {@link #singleFlight} <b>不要</b>用完就 remove —— 移除会让等待线程
 *       computeIfAbsent 出一把新锁，互斥与双重检查同时失效。</li>
 * </ol>
 *
 * <p>判据只有一个：<b>20 个并发线程查同一个不存在的 id，{@link #loadCount()} 恰好增加 1</b>。</p>
 */
@Component
public class ProductCacheLoader {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheLoader.class);

    private final ProductMapper productMapper;
    private final LongAdder loads = new LongAdder();

    /** 单飞用的分段锁：同一个 id 对应同一把锁对象。 */
    private final ConcurrentHashMap<Long, Object> singleFlight = new ConcurrentHashMap<>();

    public ProductCacheLoader(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    /** 简化入口：不查缓存、不写缓存（只执行一次加载）。 */
    public Product load(Long id) {
        return loadWithDoubleCheck(id, null, null, () -> productMapper.selectById(id)).product();
    }

    /**
     * 带双重检查的加载。
     *
     * <p>骨架里这里是 TODO —— 目前它<b>忽略</b> cachePeek/cacheWrite，直接执行 fallback。</p>
     *
     * @param cachePeek  再看一眼缓存的回调（双重检查）；{@code null} 表示不检查
     * @param cacheWrite 把加载结果写回缓存的回调；{@code null} 表示不写。
     *                   <b>必须在持锁状态下调用它</b>，否则防不住击穿。
     * @return {@link CacheHit}：{@code hit=true} 表示结果来自缓存（本次没打 DB）
     */
    public CacheHit loadWithDoubleCheck(Long id,
                                       Function<Long, CacheHit> cachePeek,
                                       Consumer<Product> cacheWrite,
                                       Supplier<Product> fallback) {
        // TODO(Stage 5): 这里要实现"单飞 + 双重检查 + 临界区内写缓存"，防止缓存击穿。
        //   提示：singleFlight、loads、cachePeek、cacheWrite 都是为你准备好的。
        loads.increment();
        Product product = (fallback == null) ? productMapper.selectById(id) : fallback.get();
        log.debug("cache load product id={} -> {}", id, product == null ? "MISS(不存在)" : product.getName());
        if (cacheWrite != null) {
            cacheWrite.accept(product);
        }
        return CacheHit.loaded(product);
    }

    /** 真实打到数据库的加载次数（验收测试据此断言防击穿是否生效）。 */
    public long loadCount() {
        return loads.sum();
    }

    /** 归零计数（测试用）。 */
    public void resetLoadCount() {
        loads.reset();
    }
}
