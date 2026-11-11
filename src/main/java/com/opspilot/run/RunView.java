package com.opspilot.run;

import com.opspilot.config.JsonCodec;
import com.opspilot.store.WorkflowRunEntity;

import java.time.LocalDateTime;
import java.util.Map;

public record RunView(String id, String taskId, String poNumber, String status, String currentStep,
                      Map<String, Object> data, String errorType, String errorMessage,
                      LocalDateTime retryAt, Integer retryCount, String leaseOwner, LocalDateTime createdAt, LocalDateTime updatedAt,
                      LocalDateTime startedAt, LocalDateTime finishedAt) {

    public static RunView of(WorkflowRunEntity run, JsonCodec json) {
        return new RunView(run.getId(), run.getTaskId(), run.getPoNumber(), run.getStatus(), run.getCurrentStep(),
                json.readMap(run.getData()), run.getErrorType(), run.getErrorMessage(), run.getRetryAt(),
                run.getRetryCount(), run.getLeaseOwner(),
                run.getCreatedAt(), run.getUpdatedAt(), run.getStartedAt(), run.getFinishedAt());
    }
}
