package com.devteam.seckill.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;

/**
 * 基于 Redis 的分布式锁实现（Stage 6 参考解）。
 *
 * <h2>为什么是这两条命令</h2>
 * <ol>
 *   <li><b>抢锁</b>：{@code SET key token NX PX ttl} —— 一条命令同时做到"key 不存在才写入"
 *       （NX，保证互斥）与"设置过期时间"（PX，保证不会永久死锁）。若拆成先 {@code SETNX}
 *       再 {@code EXPIRE}，两条命令之间进程崩溃就会留下永不过期的锁（经典的锁泄漏）。</li>
 *   <li><b>释放锁</b>：必须"校验 token + 删除"原子完成，所以用 Lua 脚本（Redis 单线程执行脚本，
 *       脚本内部不会被插队）。若直接 {@code DEL key}，会出现误删：持锁线程 GC 停顿导致锁过期、
 *       别的线程拿到锁之后，前一个线程醒来一 DEL 就把新持有者的锁删了，互斥当场失效。</li>
 * </ol>
 *
 * <h2>这套方案的边界（诚实记账）</h2>
 * <ul>
 *   <li>TTL 到期 = 自动释放：安全（不会死锁），但业务若跑得比 TTL 长就失去保护 → 需要续期（看门狗）。</li>
 *   <li>Redis 主从异步复制：主节点写入成功但未同步就宕机，锁可能丢（Redlock 想解决这个问题，
 *       但代价与争议都很大）——本项目不展开。</li>
 * </ul>
 */
@Service
public class RedisLockService implements LockService {

    /**
     * 释放锁：只有 value 与自己的 token 逐字相等时才 DEL。
     * 返回 1 表示确实删掉了（锁曾属于我），0 表示锁已过期/已被他人持有（我什么都不做）。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryLock(String key, String token, long ttlMillis) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void unlock(String key, String token) {
        redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }

    @Override
    public boolean isHeldBy(String key, String token) {
        String current = redis.opsForValue().get(key);
        return token != null && token.equals(current);
    }

    /** 续期（看门狗用）：只有仍是自己的锁才续。 */
    public boolean renew(String key, String token, long ttlMillis) {
        if (!isHeldBy(key, token)) {
            return false;
        }
        return Boolean.TRUE.equals(redis.expire(key, Duration.ofMillis(ttlMillis)));
    }
}
