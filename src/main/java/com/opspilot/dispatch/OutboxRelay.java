package com.opspilot.dispatch;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.OutboxMessageEntity;
import com.opspilot.store.OutboxMessageMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 发件箱投递器：把 PENDING 的消息投递出去（MQ 或本地线程池），确认后标记 SENT。
 *
 * <ul>
 *   <li>触发：业务事务提交后调 {@link #kick()} 立即投递（毫秒级）；{@link OutboxScheduler} 每秒扫一次兜底</li>
 *   <li>领取：{@code FOR UPDATE SKIP LOCKED} + 推后 next_attempt_at，多个投递器同时跑也不会重复领取；
 *       领取的事务马上提交，投递（网络调用）在事务外</li>
 *   <li>失败：指数退避重试（2、4、8…最多 60 秒），超过次数标记 DEAD；运行本身由恢复扫描兜底，不会因此卡死</li>
 *   <li>投递器在"已投递、还没标记 SENT"时崩溃：领取到期后会再投一次。所以是<b>至少一次</b>投递，
 *       消费端靠执行租约去重（{@code RunWorker.executeNow}）</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMessageMapper mapper;
    private final DispatchPublisher publisher;
    private final TaskExecutor kickExecutor;
    private final TransactionTemplate claimTx;
    private final OpsPilotProperties.Dispatch config;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxMessageMapper mapper, DispatchPublisher publisher,
                       @Qualifier("outboxRelayExecutor") TaskExecutor kickExecutor,
                       PlatformTransactionManager transactionManager, OpsPilotProperties properties,
                       MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.kickExecutor = kickExecutor;
        this.claimTx = new TransactionTemplate(transactionManager);
        // 和恢复扫描一样用读已提交：只锁符合条件的行，不加间隙锁，不挡业务事务插入新消息
        this.claimTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.config = properties.dispatch();
        this.published = Counter.builder("opspilot.outbox.published").description("投递成功的发件箱消息数")
                .register(meterRegistry);
        this.failed = Counter.builder("opspilot.outbox.failed").description("投递失败（稍后重试）的次数")
                .register(meterRegistry);
    }

    /**
     * 业务事务提交后调用：马上投递一轮，不等定时扫描。
     * 投递线程池只有 1 个线程、队列长度 1，满了直接丢弃这次触发：队列里已经有一轮在等，它会把这条也投出去。
     */
    public void kick() {
        try {
            kickExecutor.execute(this::relaySafely);
        } catch (TaskRejectedException e) {
            // 已经有一轮在排队，不用重复触发
        }
    }

    private void relaySafely() {
        try {
            relayOnce();
        } catch (RuntimeException e) {
            // 数据库暂时不可用等：消息还在 PENDING，定时扫描会再来
            log.warn("发件箱投递失败: {}", e.getMessage());
        }
    }

    /** 投递一轮，返回本轮领取的消息数。 */
    public int relayOnce() {
        return relayOnce(publisher);
    }

    /** 用指定的投递方式投递一轮（测试里用来模拟 MQ 故障）。 */
    public int relayOnce(DispatchPublisher via) {
        List<OutboxMessageEntity> batch = claimBatch();
        DispatchException brokerDown = null;
        for (OutboxMessageEntity message : batch) {
            if (brokerDown != null) {
                // 前一条刚失败（多半是 MQ 连不上），剩下的不再逐条等超时，一起推迟
                markFailed(message, "跳过：同一轮前面的消息投递失败（" + brokerDown.getMessage() + "）");
                continue;
            }
            try {
                via.publish(message);
                mapper.markSent(message.getId());
                published.increment();
            } catch (RuntimeException e) {
                brokerDown = e instanceof DispatchException de ? de : new DispatchException(e.getMessage(), e);
                markFailed(message, e.getMessage());
                log.warn("发件箱消息 {}（运行 {}）第 {} 次投递失败: {}", message.getId(), message.getMessageKey(),
                        message.getAttempts(), e.getMessage());
            }
        }
        return batch.size();
    }

    /** 领取一批到期的消息："锁住 + 推后 next_attempt_at"在一个短事务里，提交后就释放行锁。 */
    List<OutboxMessageEntity> claimBatch() {
        List<OutboxMessageEntity> batch = claimTx.execute(s -> {
            List<Long> ids = mapper.lockDue(config.relayBatchSize());
            if (ids.isEmpty()) {
                return List.<OutboxMessageEntity>of();
            }
            mapper.claim(ids, config.claimSeconds());
            return mapper.selectByIdList(ids);
        });
        return batch == null ? List.of() : batch;
    }

    private void markFailed(OutboxMessageEntity message, String error) {
        failed.increment();
        int attempts = message.getAttempts() == null ? 1 : message.getAttempts();
        mapper.markFailed(message.getId(), backoffSeconds(attempts), truncate(error), config.maxAttempts());
    }

    /** 第 n 次失败后等 2^n 秒，最多 60 秒。 */
    static int backoffSeconds(int attempts) {
        return (int) Math.min(60, 1L << Math.min(Math.max(attempts, 1), 6));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "unknown";
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
