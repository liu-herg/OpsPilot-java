package com.opspilot.gateway;

/**
 * 提交付款失败。按"对方到底有没有收到"分三种，处理方式完全不同：
 * <ul>
 *   <li>NOT_SENT：连接都没建立，对方肯定没收到 → 释放幂等记录，可以立即重试</li>
 *   <li>OUTCOME_UNKNOWN：请求可能已经到达（读超时、5xx、响应丢失）→ 保留记录，下次先对账再决定</li>
 *   <li>REJECTED：对方明确拒绝（4xx）→ 重试也没用，运行失败</li>
 * </ul>
 */
public class SubmitException extends RuntimeException {

    public enum Kind { NOT_SENT, OUTCOME_UNKNOWN, REJECTED }

    private final Kind kind;

    public SubmitException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public SubmitException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
