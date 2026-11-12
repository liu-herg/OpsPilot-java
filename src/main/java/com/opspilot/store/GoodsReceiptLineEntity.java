package com.opspilot.store;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/** 收货明细，对应表 goods_receipt_line。 */
@TableName("goods_receipt_line")
public class GoodsReceiptLineEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long grId;
    private Long poLineId;
    private BigDecimal quantity;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGrId() { return grId; }
    public void setGrId(Long grId) { this.grId = grId; }
    public Long getPoLineId() { return poLineId; }
    public void setPoLineId(Long poLineId) { this.poLineId = poLineId; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
}
