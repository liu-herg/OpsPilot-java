package com.opspilot.dispatch;

/** 投递失败：MQ 不可用、没有确认、消息无法路由等。 */
public class DispatchException extends RuntimeException {

    public DispatchException(String message) {
        super(message);
    }

    public DispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
