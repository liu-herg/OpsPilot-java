package com.opspilot.run;

import java.time.LocalDateTime;

/** 一次状态转移要写的字段；为 null 的字段不更新。 */
public final class RunUpdate {

    private final RunStatus status;
    private String currentStep;
    private String data;
    private String errorType;
    private String errorMessage;
    private LocalDateTime retryAt;
    private boolean clearRetryAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
    private Integer retryAfterSeconds;
    private boolean resetRetryCount;
    private String leaseOwner;
    private int leaseSeconds;

    private RunUpdate(RunStatus status) {
        this.status = status;
    }

    public static RunUpdate to(RunStatus status) {
        return new RunUpdate(status);
    }

    public RunUpdate step(String step) { this.currentStep = step; return this; }
    public RunUpdate data(String json) { this.data = json; return this; }
    public RunUpdate error(String type, String message) { this.errorType = type; this.errorMessage = message; return this; }
    public RunUpdate retryAt(LocalDateTime at) { this.retryAt = at; return this; }
    public RunUpdate clearRetryAt() { this.clearRetryAt = true; return this; }
    public RunUpdate started(LocalDateTime at) { this.startedAt = at; return this; }
    public RunUpdate finished(LocalDateTime at) { this.finishedAt = at; return this; }
    /** 由数据库时钟计算下次重试时间（NOW() + seconds），并把 retry_count + 1。 */
    public RunUpdate retryAfter(int seconds) { this.retryAfterSeconds = seconds; return this; }
    public RunUpdate resetRetryCount() { this.resetRetryCount = true; return this; }
    /** 以租约持有者身份转移：只有租约仍是 owner 的才能成功，成功同时续租。 */
    public RunUpdate ownedBy(String owner) { this.leaseOwner = owner; return this; }
    RunUpdate updatedAt(LocalDateTime at) { this.updatedAt = at; return this; }
    RunUpdate leaseSeconds(int seconds) { this.leaseSeconds = seconds; return this; }

    // 下面的 getter 给 MyBatis 读参数用
    public RunStatus getStatus() { return status; }
    public String getCurrentStep() { return currentStep; }
    public String getData() { return data; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getRetryAt() { return retryAt; }
    public boolean isClearRetryAt() { return clearRetryAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
    public boolean isResetRetryCount() { return resetRetryCount; }
    public String getLeaseOwner() { return leaseOwner; }
    public int getLeaseSeconds() { return leaseSeconds; }
}
