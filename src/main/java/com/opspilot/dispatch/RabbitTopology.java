package com.opspilot.dispatch;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑（dispatch.mode=mq 时才创建，启动时由 RabbitAdmin 自动声明）：
 * <pre>
 * opspilot.run (direct) ──run.dispatch──▶ opspilot.run.dispatch ──消费失败──▶ opspilot.run.dlx ──▶ opspilot.run.dispatch.dlq
 * </pre>
 * 交换机、队列都是持久化的，消息也是持久化的（PERSISTENT），MQ 重启不丢。
 * 死信队列只收"坏消息"（运行不存在、消息为空、消费时数据库出错），供排查；运行本身有恢复扫描兜底。
 */
@Configuration
@ConditionalOnProperty(name = "opspilot.dispatch.mode", havingValue = "mq")
public class RabbitTopology {

    public static final String EXCHANGE = "opspilot.run";
    public static final String ROUTING_KEY = "run.dispatch";
    public static final String QUEUE = "opspilot.run.dispatch";
    public static final String DEAD_LETTER_EXCHANGE = "opspilot.run.dlx";
    public static final String DEAD_LETTER_QUEUE = "opspilot.run.dispatch.dlq";

    @Bean
    public DirectExchange runExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange runDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue runDispatchQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue runDispatchDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding runDispatchBinding(Queue runDispatchQueue, DirectExchange runExchange) {
        return BindingBuilder.bind(runDispatchQueue).to(runExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding runDispatchDeadLetterBinding(Queue runDispatchDeadLetterQueue,
                                                DirectExchange runDeadLetterExchange) {
        return BindingBuilder.bind(runDispatchDeadLetterQueue).to(runDeadLetterExchange).with(ROUTING_KEY);
    }
}
