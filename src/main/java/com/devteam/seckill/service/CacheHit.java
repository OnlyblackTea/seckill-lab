package com.devteam.seckill.service;

import com.devteam.seckill.domain.Product;

/**
 * 缓存加载结果（Stage 5 参考解的内部载体）。
 *
 * <p>为什么需要它：普通 {@code Product} 的 {@code null} 有<b>两种完全不同的含义</b>——</p>
 * <ul>
 *   <li>{@code hit=false, product=null}：缓存里<b>什么都没有</b>，必须去查 DB；</li>
 *   <li>{@code hit=true, product=null}：缓存里存的是<b>空值哨兵</b>，说明"这个 id 确定不存在"，
 *       直接返回 null 即可，<b>绝不能再去查 DB</b>。</li>
 * </ul>
 *
 * <p>把这两种含义混在一个 {@code null} 里，就是"空值缓存看起来写了、却完全挡不住并发穿透"的根因：
 * 20 个线程同时查同一个不存在的 id，第一个线程查出 null 并写入哨兵，其余 19 个在临界区里
 * 做双重检查时只能看到 {@code null}——它分不清"没缓存"还是"缓存了空值"，于是 19 个线程
 * 又各查了一次 DB。加上这个显式标志后，判据从 20 次降到 1 次。</p>
 *
 * @param product 商品；为 {@code null} 时表示"不存在"（配合 {@code hit=true} 表示这是缓存里的空值哨兵）
 * @param hit     本次结果是否<b>直接来自缓存</b>（含空值哨兵）；{@code false} 表示真的查了一次 DB
 */
public record CacheHit(Product product, boolean hit) {

    /** 缓存里就有（可能是商品，也可能是"确定不存在"的空值哨兵）。 */
    public static CacheHit cached(Product product) {
        return new CacheHit(product, true);
    }

    /** 缓存里没有，这次真的查了 DB。 */
    public static CacheHit loaded(Product product) {
        return new CacheHit(product, false);
    }
}
