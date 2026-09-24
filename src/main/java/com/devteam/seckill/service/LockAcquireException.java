package com.devteam.seckill.service;

/**
 * 获取分布式锁失败（Stage 6 选修）。
 *
 * <p>语义约定：超时拿不到锁属于**可预期的业务失败**（让调用方决定重试或快速失败），
 * 不是系统错误。所以它继承 {@link RuntimeException} 只是为了不强迫调用方 catch，
 * 但调用方应当把它当成"这次没抢到"而不是"服务挂了"。</p>
 */
public class LockAcquireException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LockAcquireException(String key, long timeoutMillis) {
        super("获取锁超时：" + key + "（等待 " + timeoutMillis + "ms）");
    }
}
