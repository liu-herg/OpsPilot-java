package com.opspilot.dispatch;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.OutboxMessageEntity;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 投递到 RabbitMQ，并<b>等 broker 确认</b>（publisher confirm）才算成功：
 * <ul>
 *   <li>broker 回 ack：消息已经持久化到队列，发件箱才能标记 SENT</li>
 *   <li>broker 回 nack、超时没回、连接断了：抛异常，消息留在发件箱里稍后重投</li>
 *   <li>消息被退回（mandatory，交换机没有绑定队列）：也算失败，否则消息会被 broker 悄悄丢掉</li>
 * </ul>
 * 消息体就是运行 id，messageId 用发件箱 id，方便在 MQ 管理界面里对上。
 */
@Component
@ConditionalOnProperty(name = "opspilot.dispatch.mode", havingValue = "mq")
public class RabbitDispatchPublisher implements DispatchPublisher {

    private final RabbitTemplate rabbit;
    private final long confirmTimeoutMs;

    public RabbitDispatchPublisher(RabbitTemplate rabbit, OpsPilotProperties properties) {
        this.rabbit = rabbit;
        this.confirmTimeoutMs = properties.dispatch().confirmTimeoutMs();
    }

    @Override
    public void publish(OutboxMessageEntity outbox) {
        String id = "outbox-" + outbox.getId();
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setMessageId(id);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader("reason", outbox.getReason());
        Message message = new Message(outbox.getMessageKey().getBytes(StandardCharsets.UTF_8), props);

        CorrelationData correlation = new CorrelationData(id);
        try {
            rabbit.send(RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_KEY, message, correlation);
        } catch (AmqpException e) {
            throw new DispatchException("连不上 RabbitMQ: " + e.getMessage(), e);
        }

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new DispatchException("等待 RabbitMQ 确认超时（" + confirmTimeoutMs + "ms）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DispatchException("等待 RabbitMQ 确认时被中断");
        } catch (ExecutionException e) {
            throw new DispatchException("RabbitMQ 确认失败: " + e.getCause(), e);
        }
        if (!confirm.isAck()) {
            throw new DispatchException("RabbitMQ 拒绝了消息: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new DispatchException("消息无法路由到队列: " + correlation.getReturned().getReplyText());
        }
    }
}
