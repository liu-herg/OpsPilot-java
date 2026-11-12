package com.opspilot.store;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 三单匹配差异，对应表 match_exception。 */
@TableName("match_exception")
public class MatchExceptionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long invoiceId;
    private Long poLineId;
    private String type;
    private String detail;
    private String status;
    private String resolver;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }
    public Long getPoLineId() { return poLineId; }
    public void setPoLineId(Long poLineId) { this.poLineId = poLineId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolver() { return resolver; }
    public void setResolver(String resolver) { this.resolver = resolver; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
