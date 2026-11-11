package com.opspilot.run;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 运行状态机（替代 Python 版里的 LangGraph 图）。
 *
 * <pre>
 * QUEUED ──start──▶ RUNNING ──金额 ≤ 阈值──▶ READY_TO_SUBMIT ──付款已确认──▶ COMPLETED
 *                      │                         ▲    │ ↺ 提交结果不确定 / 被占用：留在原状态，稍后重试
 *                      │                         │    └──财务明确拒绝──▶ FAILED
 *                      ├──金额 > 阈值──▶ WAITING_APPROVAL ──批准──┘
 *                      │                         └──驳回──▶ REJECTED
 *                      └──数据不一致 / 查不到──▶ FAILED
 * </pre>
 * 转移是否合法在这里检查，转移本身由 {@code WHERE id = ? AND status = ?} 的条件更新保证原子性。
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    READY_TO_SUBMIT,
    COMPLETED,
    REJECTED,
    FAILED;

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
            QUEUED, EnumSet.of(RUNNING, FAILED),
            // RUNNING → RUNNING：进入下一个步骤，只更新 current_step
            RUNNING, EnumSet.of(RUNNING, WAITING_APPROVAL, READY_TO_SUBMIT, FAILED),
            WAITING_APPROVAL, EnumSet.of(READY_TO_SUBMIT, REJECTED),
            // READY_TO_SUBMIT → READY_TO_SUBMIT：提交被推迟，记录下次重试时间
            READY_TO_SUBMIT, EnumSet.of(READY_TO_SUBMIT, COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(RunStatus.class),
            REJECTED, EnumSet.noneOf(RunStatus.class),
            FAILED, EnumSet.noneOf(RunStatus.class)
    );

    public boolean canTransitionTo(RunStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * 需要后台执行者推进的状态：进程崩溃后，恢复扫描会接手这些状态里没人持有租约的运行。
     * QUEUED 不算（用户还没点启动），WAITING_APPROVAL 也不算（在等人，不是卡住了）。
     */
    public boolean isRecoverable() {
        return this == RUNNING || this == READY_TO_SUBMIT;
    }
}
