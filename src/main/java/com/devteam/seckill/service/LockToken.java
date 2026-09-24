package com.devteam.seckill.service;

/**
 * 分布式锁的持有者令牌。
 *
 * <p>Stage 6 的关键约定：分布式锁的 value **不能**是固定字符串（如 {@code "1"}），
 * 必须是每次抢锁都不同的唯一 token。原因：释放锁时"判断是不是自己的锁"和"删除"
 * 必须是原子的，而判断的依据就是这个 token——</p>
 *
 * <pre>
 *   线程 A 抢到锁（TTL 5s） → A GC 停顿 6s → 锁自动过期 → 线程 B 抢到锁
 *   → A 醒来执行 unlock：如果按 key 直接 DEL，就把 B 的锁删了（误删）
 *   → 必须比对 token：A 的 token ≠ B 的 token → 不删
 * </pre>
 *
 * @param key      锁的 key（约定形如 {@code lock:seckill:{productId}}）
 * @param token    唯一令牌（UUID），只有它对应持有者有权释放
 * @param owner    持有者的可读标识（如线程名 / 实例名，仅用于排查，不参与鉴权）
 * @param acquiredAt 抢锁成功的时间戳（毫秒），用于算持有时长
 */
public record LockToken(String key, String token, String owner, long acquiredAt) {

    public static LockToken of(String key, String owner) {
        return new LockToken(key, java.util.UUID.randomUUID().toString(), owner, System.currentTimeMillis());
    }

    public long heldMillis() {
        return System.currentTimeMillis() - acquiredAt;
    }
}
