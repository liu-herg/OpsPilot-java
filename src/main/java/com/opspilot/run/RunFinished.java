package com.opspilot.run;

/**
 * 运行进入终态（COMPLETED / FAILED / REJECTED）。在状态转移的同一个事务里同步发布，
 * 监听者（例如付款单状态同步）的修改和运行状态一起提交或回滚。
 */
public record RunFinished(String runId, RunStatus status) {
}
