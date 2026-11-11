package com.opspilot.run;

import com.opspilot.store.WorkflowRunMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 所有状态转移的唯一入口：先检查转移是否合法，再做条件更新。 */
@Component
public class RunStore {

    private final WorkflowRunMapper runMapper;

    public RunStore(WorkflowRunMapper runMapper) {
        this.runMapper = runMapper;
    }

    /**
     * @return true 表示转移成功；false 表示当前状态已经不是 from（被并发修改了），本次什么都没改
     * @throws IllegalStateException 状态机不允许 from → 目标状态（代码 bug，不是并发问题）
     */
    public boolean transition(String runId, RunStatus from, RunUpdate update) {
        if (!from.canTransitionTo(update.getStatus())) {
            throw new IllegalStateException("非法状态转移: " + from + " -> " + update.getStatus());
        }
        update.updatedAt(LocalDateTime.now());
        return runMapper.compareAndSet(runId, from, update) == 1;
    }
}
