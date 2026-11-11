package com.opspilot.gateway;

/** 只读查询失败（业务系统不可用、超时、5xx）。查询没有副作用，可以放心重试。 */
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
