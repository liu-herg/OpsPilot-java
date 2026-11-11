package com.opspilot.approval;

public enum DecisionOutcome {
    /** 本次请求做出了决定 */
    RESOLVED,
    /** 之前已经做过同样的决定（重复提交），幂等返回 */
    ALREADY_RESOLVED
}
