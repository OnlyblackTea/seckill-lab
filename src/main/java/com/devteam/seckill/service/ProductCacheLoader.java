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
 * 商品「缓存未命中时的加载器」（Stage 5 防击穿的参考解）。
 *
 * <h2>防击穿 = 双重检查 + "查 DB + 写缓存"必须在同一个临界区里</h2>
 * <p>这一版经过真机实测校准，三个坑缺一不可：</p>
 * <ol>
 *   <li><b>只加锁没用。</b>20 个线程同时发现未命中，会都走进本方法；如果临界区里不"再看一眼缓存"，
 *       它们只是排队、然后各查一次 DB —— 串行化了，但还是 20 次查询。</li>
 *   <li><b>双重检查还不够，必须把"写缓存"也放进临界区。</b>这是最隐蔽的一个：
 *       如果"查 DB"在锁里、"写缓存"在锁外（服务层调用完再写），那么等待中的线程醒来时
 *       缓存<b>还是空的</b>（前一个线程还没写回去），于是它也去查一次 DB。
 *       实测表现：锁明明生效、双重检查也在命中，DB 加载次数却只从 20 降到 4~6。
 *       所以 {@code cacheWrite} 回调必须在持锁状态下执行。</li>
 *   <li><b>"缓存了空值"要和"没有缓存"区分开。</b>否则负缓存（防穿透的哨兵）在双重检查里
 *       看着还是 null，等待线程又去查一次 DB。这就是 {@link CacheHit} 存在的理由。</li>
 * </ol>
 *
 * <h2>与 Stage 6 分布式锁的分工</h2>
 * <p>本类用的是<b>进程内</b>分段锁：一个 JVM 内的并发够用（缓存重建本来就是同进程的事）。
 * 跨进程互斥（多实例部署防重复扣减）才需要 Redis 分布式锁 —— 见 {@link LockService}。</p>
 */
@Component
public class ProductCacheLoader {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheLoader.class);

    private final ProductMapper productMapper;
    private final LongAdder loads = new LongAdder();

    /**
     * 单飞用的分段锁：同一个 id 对应同一把锁对象。
     *
     * <p>锁对象<b>故意不清理</b>：它的生命周期就该和这个 id 一样长。一个 id 一个空对象的内存
     * 开销可以忽略，换来的是"所有等待线程拿到的一定是同一把锁"这个正确性前提。
     * 如果加载完就 remove，等待线程醒来会 computeIfAbsent 出一把<b>新</b>锁，
     * 互斥与双重检查同时失效。</p>
     */
    private final ConcurrentHashMap<Long, Object> singleFlight = new ConcurrentHashMap<>();

    public ProductCacheLoader(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    /** 简化入口：不查缓存、不写缓存（只把加载串行化——防不住击穿，留作对照）。 */
    public Product load(Long id) {
        return loadWithDoubleCheck(id, null, null, () -> productMapper.selectById(id)).product();
    }

    /**
     * 带双重检查的加载：<b>"再看一眼缓存 → 查 DB → 写缓存"全部在同一个临界区里完成</b>。
     *
     * @param cachePeek  再看一眼缓存的回调：{@code null} 表示没有缓存；返回 {@link CacheHit#cached}
     *                   （product 可以是 null，代表负缓存）表示"别人已经加载好了"，直接复用。
     * @param cacheWrite 把加载结果写回缓存的回调（<b>必须在持锁状态下执行</b>）；
     *                   传 {@code null} 表示不写缓存。product 为 null 时表示要写"空值哨兵"。
     * @return {@link CacheHit}：{@code hit=true} 表示结果来自缓存（本次没打 DB）
     */
    public CacheHit loadWithDoubleCheck(Long id,
                                       Function<Long, CacheHit> cachePeek,
                                       Consumer<Product> cacheWrite,
                                       Supplier<Product> fallback) {
        Object lock = singleFlight.computeIfAbsent(id, k -> new Object());
        synchronized (lock) {
            if (cachePeek != null) {
                CacheHit peeked = cachePeek.apply(id);
                if (peeked != null && peeked.hit()) {
                    log.debug("single-flight double-check hit for id={} (product={})",
                            id, peeked.product() == null ? "NULL(负缓存)" : "exists");
                    return peeked;
                }
            }
            loads.increment();   // 真正打 DB 的只有拿到锁、且缓存仍无有效结果的这一个线程
            Product product = (fallback == null) ? productMapper.selectById(id) : fallback.get();
            log.debug("cache load product id={} -> {}",
                    id, product == null ? "MISS(不存在)" : product.getName());
            if (cacheWrite != null) {
                // 关键：在持锁状态下写缓存。这样下一个拿到锁的线程一定能看到它，从而跳过 DB。
                cacheWrite.accept(product);
            }
            return CacheHit.loaded(product);
        }
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
