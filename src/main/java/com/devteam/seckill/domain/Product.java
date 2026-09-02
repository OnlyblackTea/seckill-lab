package com.devteam.seckill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品（含秒杀库存）。对应表 product。
 * 纯 POJO + getter/setter，刻意不用 Lombok，方便新手直接读懂字段映射。
 */
public class Product {

    private Long id;
    private String name;
    private Long categoryId;
    /** 1=上架 0=下架 */
    private Integer status;
    private BigDecimal price;
    private Integer stock;
    /** 乐观锁版本号（Stage 3 用） */
    private Integer version;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
