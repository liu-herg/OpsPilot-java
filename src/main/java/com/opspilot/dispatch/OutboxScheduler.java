package com.opspilot.dispatch;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.OutboxMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发件箱的定时任务：
 * <ul>
 *   <li>兜底投递：{@code kick()} 被丢弃、投递失败后到了重试时间、投递器崩溃后领取到期，都靠这里再投</li>
 *   <li>清理：已投递超过保留天数的消息分批删除</li>
 * </ul>
 */
@Component
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxRelay relay;
    private final OutboxMessageMapper mapper;
    private final int retentionDays;

    public OutboxScheduler(OutboxRelay relay, OutboxMessageMapper mapper, OpsPilotProperties properties) {
        this.relay = relay;
        this.mapper = mapper;
        this.retentionDays = properties.dispatch().retentionDays();
    }

    @Scheduled(fixedDelayString = "${opspilot.dispatch.relay-poll-ms:1000}",
            initialDelayString = "${opspilot.dispatch.relay-poll-ms:1000}")
    public void relay() {
        try {
            relay.relayOnce();
        } catch (RuntimeException e) {
            log.warn("发件箱兜底投递失败: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanUp() {
        try {
            int deleted;
            do {
                deleted = mapper.deleteSentBefore(retentionDays, 1000);
            } while (deleted == 1000);
        } catch (RuntimeException e) {
            log.warn("清理发件箱失败: {}", e.getMessage());
        }
    }
}
