package com.opspilot.store;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 审批，对应表 approval。payload 是 JSON 字符串。 */
@TableName("approval")
public class ApprovalEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String runId;
    private String risk;
    private String payload;
    private String status;
    private String decisionActor;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecisionActor() { return decisionActor; }
    public void setDecisionActor(String decisionActor) { this.decisionActor = decisionActor; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
