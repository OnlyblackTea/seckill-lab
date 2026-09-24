package com.devteam.seckill.service;

import org.springframework.stereotype.Service;

/**
 * {@link LockService} 的<b>骨架占位实现</b>（Stage 6 你要把本类改成真正的 Redis 分布式锁）。
 *
 * <p>为什么骨架里要有这么一个"只会抛异常"的实现：验收测试按接口注入
 * （{@code @Autowired LockService}）。如果容器里**一个**实现都没有，Spring 启动时就会报
 * {@code NoSuchBeanDefinitionException}——那测试就"红在错误的地方"（红在装配失败，
 * 而不是红在业务逻辑没写）。所以骨架提供本类，让三个方法明确抛
 * {@code UnsupportedOperationException}，把红线钉在"锁逻辑尚未实现"上。</p>
 *
 * <h2>Stage 6 你要做的事</h2>
 * <ol>
 *   <li>{@link #tryLock} → Redis {@code SET key token NX PX ttl}：一条命令同时完成
 *       "不存在才写入"（互斥）与"设置过期"（防死锁）；</li>
 *   <li>{@link #unlock} → 先比对 token 再删除，且两步必须原子（Lua 脚本），防止误删；</li>
 *   <li>{@link #isHeldBy} → 读出当前 value 与 token 比较；</li>
 *   <li>让 {@link SeckillService} 的下单路径按固定顺序加锁，消除死锁的循环等待。</li>
 * </ol>
 *
 * <p>提示：本类可以改名（例如 {@code RedisLockService}），但**必须保证容器里只有一个
 * {@link LockService} 实现**，否则会红在 {@code NoUniqueBeanDefinitionException} 上。</p>
 */
@Service
public class LockServiceImpl implements LockService {

    @Override
    public boolean tryLock(String key, String token, long ttlMillis) {
        throw new UnsupportedOperationException(
                "分布式锁尚未实现（Stage 6）：tryLock 需要用 Redis 的 SET key token NX PX ttl 原子抢锁");
    }

    @Override
    public void unlock(String key, String token) {
        throw new UnsupportedOperationException(
                "分布式锁尚未实现（Stage 6）：unlock 需要先比对 token 再删除，且两步要原子（Lua）");
    }

    @Override
    public boolean isHeldBy(String key, String token) {
        throw new UnsupportedOperationException(
                "分布式锁尚未实现（Stage 6）：isHeldBy 需要读取锁的 value 与 token 比较");
    }
}
