package com.opspilot.run;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.WorkflowRunMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 所有状态转移的唯一入口：先检查转移是否合法，再做条件更新。 */
@Component
public class RunStore {

    private final WorkflowRunMapper runMapper;
    private final int leaseSeconds;
    private final ApplicationEventPublisher publisher;

    public RunStore(WorkflowRunMapper runMapper, OpsPilotProperties properties, ApplicationEventPublisher publisher) {
        this.runMapper = runMapper;
        this.leaseSeconds = properties.worker().leaseSeconds();
        this.publisher = publisher;
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
        boolean changed = runMapper.compareAndSet(runId, from, update) == 1;
        if (changed && update.getStatus().isTerminal()) {
            // 同步发布：监听者在当前事务里执行（付款单 PAID / FAILED 和运行终态一起提交）
            publisher.publishEvent(new RunFinished(runId, update.getStatus()));
        }
        return changed;
    }

    /** 指数退避：5s、10s、20s … 最长 5 分钟。 */
    public static int backoffSeconds(int retryCount) {
        return (int) Math.min(300, 5L << Math.min(Math.max(retryCount, 0), 6));
    }
}
