package com.devteam.seckill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 秒杀订单。对应表 seckill_order。 */
public class SeckillOrder {

    private Long id;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal amount;
    /** 0=已创建 1=已支付 2=已取消 */
    private Integer status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
