package com.opspilot.store;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 收货单，对应表 goods_receipt。 */
@TableName("goods_receipt")
public class GoodsReceiptEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String grNumber;
    private Long poId;
    private String receiver;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGrNumber() { return grNumber; }
    public void setGrNumber(String grNumber) { this.grNumber = grNumber; }
    public Long getPoId() { return poId; }
    public void setPoId(Long poId) { this.poId = poId; }
    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
