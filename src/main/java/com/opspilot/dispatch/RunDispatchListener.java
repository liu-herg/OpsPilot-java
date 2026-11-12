package com.opspilot.dispatch;

import com.opspilot.run.RunWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 消费派发消息：在消费者线程里抢租约并执行，执行完才确认消息（acknowledge-mode=auto，方法正常返回即 ack）。
 *
 * <ul>
 *   <li>prefetch=1：每个消费者手里最多一条，执行慢的实例不会囤积消息，没开始执行的消息一直在 MQ 里，
 *       实例崩溃时未确认的消息马上重新投递给其他实例</li>
 *   <li>重复消息（发件箱重投、MQ 重投）：抢不到租约就跳过并确认，不会重复执行</li>
 *   <li>坏消息（运行不存在、消息为空）、消费时数据库出错：拒绝且不重新入队 → 死信队列；
 *       运行本身仍在 RUNNING / READY_TO_SUBMIT，恢复扫描会接手</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "opspilot.dispatch.mode", havingValue = "mq")
public class RunDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(RunDispatchListener.class);

    private final RunWorker worker;
    private final Counter executed;
    private final Counter skipped;

    public RunDispatchListener(RunWorker worker, MeterRegistry meterRegistry) {
        this.worker = worker;
        this.executed = Counter.builder("opspilot.dispatch.consumed").tag("outcome", "executed")
                .description("消费派发消息：抢到租约并执行").register(meterRegistry);
        this.skipped = Counter.builder("opspilot.dispatch.consumed").tag("outcome", "skipped")
                .description("消费派发消息：重复消息，跳过").register(meterRegistry);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE)
    public void onMessage(Message message) {
        String runId = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        if (runId.isEmpty()) {
            throw new AmqpRejectAndDontRequeueException("空的派发消息");
        }
        switch (worker.executeNow(runId, "mq")) {
            case EXECUTED -> executed.increment();
            case SKIPPED -> {
                skipped.increment();
                log.info("运行 {} 正在被执行或已结束，跳过重复的派发消息 {}", runId,
                        message.getMessageProperties().getMessageId());
            }
            case NOT_FOUND -> throw new AmqpRejectAndDontRequeueException("运行不存在: " + runId);
        }
    }
}
