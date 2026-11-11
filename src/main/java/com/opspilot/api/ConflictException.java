package com.opspilot.api;

/** 409：当前状态不允许这个操作，或者被并发修改了。 */
public class ConflictException extends RuntimeException {

    private final String currentStatus;

    public ConflictException(String message, String currentStatus) {
        super(message);
        this.currentStatus = currentStatus;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
