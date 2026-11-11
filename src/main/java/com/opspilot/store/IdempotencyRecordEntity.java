package com.opspilot.store;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 付款幂等记录，对应表 idempotency_record。时间字段都由数据库 NOW(3) 写入。 */
@TableName("idempotency_record")
public class IdempotencyRecordEntity {

    @TableId(value = "idem_key", type = IdType.INPUT)
    private String idemKey;
    private String operation;
    private String status;
    private String ownerRunId;
    private LocalDateTime leaseExpiresAt;
    private Integer attempt;
    private String result;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getIdemKey() { return idemKey; }
    public void setIdemKey(String idemKey) { this.idemKey = idemKey; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwnerRunId() { return ownerRunId; }
    public void setOwnerRunId(String ownerRunId) { this.ownerRunId = ownerRunId; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
