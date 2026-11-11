package com.opspilot.run;

public record TaskCreated(String taskId, String runId, String poNumber, String status) {
}
