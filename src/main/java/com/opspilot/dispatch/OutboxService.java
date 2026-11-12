package com.opspilot.dispatch;

import com.opspilot.store.OutboxMessageEntity;
import com.opspilot.store.OutboxMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 发件箱：把"要派发这个运行"写进 outbox_message，和触发它的状态变化在<b>同一个事务</b>里。
 *
 * <p>为什么不在事务提交后直接发 MQ：提交成功、发 MQ 之前进程崩溃，消息就丢了；
 * 反过来先发 MQ 再提交，提交失败时消费者会收到一个状态根本没改的运行。
 * 写发件箱让"状态变化"和"要发的消息"一起提交或一起回滚，真正的投递由 {@link OutboxRelay} 在事务外做。
 */
@Service
public class OutboxService {

    public static final String RUN_DISPATCH = "run.dispatch";

    private final OutboxMessageMapper mapper;

    public OutboxService(OutboxMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** 必须在事务里调用，否则发件箱就失去意义了，直接报错而不是悄悄自己开一个事务。 */
    public long enqueueDispatch(String runId, String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("发件箱消息必须和状态变化在同一个事务里写入");
        }
        OutboxMessageEntity message = new OutboxMessageEntity();
        message.setTopic(RUN_DISPATCH);
        message.setMessageKey(runId);
        message.setReason(reason);
        mapper.enqueue(message);
        return message.getId();
    }
}
