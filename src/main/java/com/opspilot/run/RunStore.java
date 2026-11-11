package com.opspilot.run;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.WorkflowRunMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 所有状态转移的唯一入口：先检查转移是否合法，再做条件更新。 */
@Component
public class RunStore {

    private final WorkflowRunMapper runMapper;
    private final int leaseSeconds;

    public RunStore(WorkflowRunMapper runMapper, OpsPilotProperties properties) {
        this.runMapper = runMapper;
        this.leaseSeconds = properties.worker().leaseSeconds();
    }

    /**
     * @return true 表示转移成功；false 表示当前状态已经不是 from（被并发修改了），
     *         或者 update 带了租约持有者、而租约已经不是它的了。两种情况本次都什么都没改
     * @throws IllegalStateException 状态机不允许 from → 目标状态（代码 bug，不是并发问题）
     */
    public boolean transition(String runId, RunStatus from, RunUpdate update) {
        if (!from.canTransitionTo(update.getStatus())) {
            throw new IllegalStateException("非法状态转移: " + from + " -> " + update.getStatus());
        }
        update.updatedAt(LocalDateTime.now());
        if (update.getLeaseOwner() != null) {
            update.leaseSeconds(leaseSeconds);
        }
        return runMapper.compareAndSet(runId, from, update) == 1;
    }

    /** 指数退避：5s、10s、20s … 最长 5 分钟。 */
    public static int backoffSeconds(int retryCount) {
        return (int) Math.min(300, 5L << Math.min(Math.max(retryCount, 0), 6));
    }
}
