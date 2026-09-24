package com.devteam.seckill.service;

/**
 * 秒杀售罄。
 *
 * <p>继承 {@link RuntimeException}（未受检）：这是刻意的——它必须能穿过
 * {@code @Transactional} 的代理触发回滚，而 Spring 默认只对
 * {@code RuntimeException} / {@code Error} 回滚。若把它做成受检异常，
 * 学员会踩到"异常抛了但库存没回滚"的经典坑。</p>
 *
 * <p>由 Stage 3（超卖与锁）使用：{@code UPDATE ... WHERE stock > 0} 返回 0 行即代表售罄。</p>
 */
public class SoldOutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long productId;

    public SoldOutException(Long productId) {
        super("商品已售罄：productId=" + productId);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
