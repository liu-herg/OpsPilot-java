package com.opspilot.approval;

import com.opspilot.config.JsonCodec;
import com.opspilot.store.ApprovalEntity;

import java.time.LocalDateTime;
import java.util.Map;

public record ApprovalView(String id, String runId, String risk, Map<String, Object> payload, String status,
                           String decisionActor, LocalDateTime createdAt, LocalDateTime resolvedAt) {

    public static ApprovalView of(ApprovalEntity a, JsonCodec json) {
        return new ApprovalView(a.getId(), a.getRunId(), a.getRisk(), json.readMap(a.getPayload()), a.getStatus(),
                a.getDecisionActor(), a.getCreatedAt(), a.getResolvedAt());
    }
}
