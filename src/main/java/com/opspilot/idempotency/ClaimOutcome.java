package com.opspilot.idempotency;

public enum ClaimOutcome {
    /** 第一次登记，由我来提交 */
    ACQUIRED,
    /** 原持有者租约过期，由我接手（接手前要先对账，确认对方没有提交成功） */
    RECLAIMED,
    /** 已经确认完成，直接用保存的结果，不要再提交 */
    COMPLETED,
    /** 别人正在提交且租约未过期，稍后再试 */
    OCCUPIED
}
