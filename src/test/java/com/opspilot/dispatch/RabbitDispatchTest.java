package com.opspilot.dispatch;

import com.opspilot.run.RunService;
import com.opspilot.run.RunWorker;
import com.opspilot.store.OutboxMessageMapper;
import com.opspilot.support.FakeGateway;
import com.opspilot.support.ItConfig;
import com.opspilot.support.MySqlProps;
import com.opspilot.support.TestData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 RabbitMQ + 真实 MySQL：dispatch.mode=mq 时，启动 → 发件箱 → RabbitMQ（publisher confirm）→ 消费者执行。
 * 需要同时设置 IT_MYSQL_URL 和 IT_RABBIT_HOST。
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IT_RABBIT_HOST", matches = ".+")
@SpringBootTest(classes = {ItConfig.class, RabbitDispatchTest.MqConfig.class},
        properties = "opspilot.dispatch.mode=mq")
class RabbitDispatchTest {

    @Configuration
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({RabbitTopology.class, RabbitDispatchPublisher.class, RunDispatchListener.class})
    static class MqConfig {
    }

    @Autowired
    private RunService runService;
    @Autowired
    private OutboxMessageMapper outboxMapper;
    @Autowired
    private RunWorker worker;
    @Autowired
    private RabbitListenerEndpointRegistry listeners;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private FakeGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> poNumbers = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        MySqlProps.register(registry);
        registry.add("spring.rabbitmq.host", () -> System.getenv("IT_RABBIT_HOST"));
        registry.add("spring.rabbitmq.port", () -> System.getenv().getOrDefault("IT_RABBIT_PORT", "5672"));
    }

    @AfterEach
    void cleanUp() {
        TestData.cleanup(jdbc, poNumbers);
        poNumbers.clear();
    }

    @Test
    void runsAreExecutedByConsumersAfterBrokerConfirms() {
        int runs = 20;
        List<String> pos = IntStream.range(0, runs).mapToObj(i -> newCase()).toList();
        List<String> runIds = pos.stream().map(po -> runService.createTask("处理 " + po).runId()).toList();

        long begin = System.nanoTime();
        runIds.forEach(runService::start);
        waitUntil(() -> runIds.stream().allMatch(id -> "COMPLETED".equals(runService.get(id).status())), 30_000);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        for (int i = 0; i < runs; i++) {
            String runId = runIds.get(i);
            assertThat(outboxMapper.selectByKey(runId)).singleElement()
                    .satisfies(m -> assertThat(m.getStatus()).isEqualTo("SENT"));
            assertThat(gateway.postCount(pos.get(i))).isEqualTo(1);
            // 是 MQ 消费者执行的，不是恢复扫描
            assertThat(runService.events(runId, 0)).anyMatch(
                    e -> "run.picked_up".equals(e.eventType()) && "mq".equals(e.payload().get("by")));
        }
        System.out.printf("[RabbitMQ 派发] %d 个运行全部完成，用时 %d ms，每个 POST 1 次%n", runs, elapsedMs);
    }

    /**
     * 演示里发现的问题：消息在 MQ 里排队时，运行是 RUNNING 且没有租约，恢复扫描把它当成"没人管"抢走了，
     * 20 个运行里 7 个是扫描执行的。修复后，刚写过派发消息的运行在宽限期内扫描不碰，过了宽限期才兜底。
     */
    @Test
    void recoveryScanLeavesQueuedRunsToConsumersUntilGraceExpires() {
        String po = newCase();
        String runId = runService.createTask("处理 " + po).runId();
        double before = skipped();
        // 停掉消费者，让消息留在队列里排队
        listeners.getListenerContainers().forEach(MessageListenerContainer::stop);
        try {
            runService.start(runId);

            worker.recoverOnce();
            assertThat(runService.get(runId).status()).isEqualTo("RUNNING");
            assertThat(runService.events(runId, 0)).noneMatch(e -> "run.picked_up".equals(e.eventType()));

            // 过了宽限期还没人消费（比如消费者全挂了）：扫描接手
            jdbc.update("UPDATE outbox_message SET created_at = DATE_SUB(NOW(3), INTERVAL 60 SECOND) "
                    + "WHERE message_key = ?", runId);
            worker.recoverOnce();
            assertThat(runService.get(runId).status()).isEqualTo("COMPLETED");
        } finally {
            listeners.getListenerContainers().forEach(MessageListenerContainer::start);
        }

        // 队列里那条消息之后被消费：运行已结束，跳过
        waitUntil(() -> skipped() >= before + 1, 15_000);
        assertThat(gateway.postCount(po)).isEqualTo(1);
    }

    @Test
    void duplicateMessagesAreSkipped() {
        String po = newCase();
        String runId = runService.createTask("处理 " + po).runId();
        runService.start(runId);
        waitUntil(() -> "COMPLETED".equals(runService.get(runId).status()), 15_000);

        double before = skipped();
        for (int i = 0; i < 3; i++) {
            rabbit.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_KEY, runId);
        }
        waitUntil(() -> skipped() >= before + 3, 15_000);

        System.out.printf("[重复消息] 同一个运行又收到 3 条派发消息：跳过 %.0f 条，POST %d 次%n",
                skipped() - before, gateway.postCount(po));
        assertThat(gateway.postCount(po)).isEqualTo(1);
    }

    @Test
    void unknownRunGoesToDeadLetterQueue() {
        amqpAdmin.purgeQueue(RabbitTopology.DEAD_LETTER_QUEUE, false);
        String bogus = "run-does-not-exist-" + System.nanoTime();

        rabbit.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_KEY, bogus);

        Message dead = rabbit.receive(RabbitTopology.DEAD_LETTER_QUEUE, 10_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(bogus);
    }

    private double skipped() {
        Counter c = meterRegistry.find("opspilot.dispatch.consumed").tag("outcome", "skipped").counter();
        return c == null ? 0 : c.count();
    }

    private String newCase() {
        String po = TestData.randomPo();
        poNumbers.add(po);
        gateway.addCase(po, "Nova Office", 7600, 7600);
        return po;
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待超时（" + timeoutMs + "ms）");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("被中断");
            }
        }
    }
}
