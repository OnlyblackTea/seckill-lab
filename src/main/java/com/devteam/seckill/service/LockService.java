package com.devteam.seckill.service;

/**
 * 分布式锁服务（Stage 6 选修的战场）。
 *
 * <p>为什么需要它：单机 {@code synchronized} / {@code ReentrantLock} 只在**一个 JVM** 内有效。
 * 秒杀服务一旦多实例部署（哪怕只起两个进程），本地锁就锁不住——
 * 两个实例各自"以为自己是唯一持有者"，于是重复扣减。跨进程互斥必须靠外部存储，
 * 这就是 Redis 分布式锁要解决的问题。</p>
 *
 * <h2>接口约定（骨架已定，Stage 6 只改实现）</h2>
 * <ul>
 *   <li>{@link #tryLock(String, String, long)}：抢锁。必须**原子**完成
 *       "不存在才写入 + 设置过期时间"，对应 Redis 的 {@code SET key value NX PX ttl}。
 *       抢不到返回 {@code false}（不阻塞）。</li>
 *   <li>{@link #unlock(String, String)}：释放锁。必须**校验 token 再删除**，
 *       且"校验 + 删除"要原子（Lua 脚本），否则会误删别人的锁。</li>
 *   <li>{@link #isHeldBy(String, String)}：当前锁是否仍被该 token 持有（测试与排查用）。</li>
 * </ul>
 *
 * <p>TTL 是这套方案的安全底线：持锁进程崩溃 / GC 长停顿 / 网络分区时，
 * 锁会因为过期而**自动释放**，不会永久死锁。代价是"业务耗时超过 TTL"会失去互斥保护，
 * 所以业务要么把 TTL 设得足够长，要么做续期（看门狗）。</p>
 *
 * <p>骨架里的实现类是 {@link LockServiceImpl}（三个方法都抛
 * {@code UnsupportedOperationException}）——把那个类改成真正的 Redis 实现即可。
 * 注意**容器里只能有一个实现**，否则会红在 {@code NoUniqueBeanDefinitionException} 上。</p>
 */
public interface LockService {

    /**
     * 尝试获取锁（非阻塞）。
     *
     * @param key       锁 key，约定形如 {@code lock:seckill:{productId}}
     * @param token     持有者令牌（必须唯一，见 {@link LockToken}）
     * @param ttlMillis 锁的存活时间（毫秒）；到期自动释放，是"防死锁"的安全网
     * @return 抢到返回 {@code true}，已被别人持有返回 {@code false}
     */
    boolean tryLock(String key, String token, long ttlMillis);

    /**
     * 释放锁。<b>只有 token 与锁中记录的一致时才删除</b>；不一致说明锁已经过期并被别人拿走，
     * 此时必须什么都不做（否则就是误删）。
     */
    void unlock(String key, String token);

    /** 锁当前是否由该 token 持有。 */
    boolean isHeldBy(String key, String token);
}
