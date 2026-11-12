package com.opspilot.dispatch;

import com.opspilot.run.RunWorker;
import com.opspilot.store.OutboxMessageEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地派发（dispatch.mode=local，默认）：直接交给本实例的线程池，不需要 MQ。
 * 和 P2 的行为一样，只是多了一层发件箱：派发意图先落库，投递器再执行。
 */
@Component
@ConditionalOnProperty(name = "opspilot.dispatch.mode", havingValue = "local", matchIfMissing = true)
public class LocalDispatchPublisher implements DispatchPublisher {

    private final RunWorker worker;

    public LocalDispatchPublisher(RunWorker worker) {
        this.worker = worker;
    }

    @Override
    public void publish(OutboxMessageEntity message) {
        // 抢不到租约（别人正在执行）也算投递成功：消息的目的已经达到
        worker.dispatch(message.getMessageKey());
    }
}
