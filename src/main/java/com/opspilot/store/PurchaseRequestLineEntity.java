package com.opspilot.store;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/** 采购申请明细，对应表 purchase_request_line。 */
@TableName("purchase_request_line")
public class PurchaseRequestLineEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long requestId;
    private Integer lineNo;
    private String itemName;
    private String spec;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal estUnitPrice;
    private BigDecimal amount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getEstUnitPrice() { return estUnitPrice; }
    public void setEstUnitPrice(BigDecimal estUnitPrice) { this.estUnitPrice = estUnitPrice; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
