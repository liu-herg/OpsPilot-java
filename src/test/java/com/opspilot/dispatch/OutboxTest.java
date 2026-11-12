package com.opspilot.dispatch;

import com.opspilot.run.RunService;
import com.opspilot.run.RunView;
import com.opspilot.run.RunWorker;
import com.opspilot.store.OutboxMessageEntity;
import com.opspilot.store.OutboxMessageMapper;
import com.opspilot.support.FakeGateway;
import com.opspilot.support.ItConfig;
import com.opspilot.support.MySqlProps;
import com.opspilot.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事务性发件箱（真实 MySQL）：消息和状态变化一起提交 / 回滚；MQ 故障时消息不丢、运行不卡；
 * 多个投递器并发不重复投递；重复投递被消费端挡住。
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = {ItConfig.class, OutboxTest.FaultConfig.class})
class OutboxTest {

    @Configuration
    static class FaultConfig {
        @Bean
        @Primary
        SwitchablePublisher switchablePublisher(LocalDispatchPublisher local) {
            return new SwitchablePublisher(local);
        }
    }

    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private OutboxService outbox;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxMessageMapper outboxMapper;
    @Autowired
    private SwitchablePublisher publisher;
    @Autowired
    private FakeGateway gateway;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> poNumbers = new CopyOnWriteArrayList<>();
    private final List<String> keyPrefixes = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        MySqlProps.register(registry);
    }

    @AfterEach
    void cleanUp() {
        publisher.setMode(SwitchablePublisher.Mode.UP);
        TestData.cleanup(jdbc, poNumbers);
        poNumbers.clear();
        for (String prefix : keyPrefixes) {
            jdbc.update("DELETE FROM outbox_message WHERE message_key LIKE ?", prefix + "%");
        }
        keyPrefixes.clear();
    }

    // ---------------------------------------------------------------- 和业务事务同生共死

    @Test
    void startWritesOneMessageThatIsSentAfterCommit() {
        String runId = runService.createTask("处理 " + newCase()).runId();

        RunView run = runService.start(runId);

        assertThat(run.status()).isEqualTo("COMPLETED");
        List<OutboxMessageEntity> messages = outboxMapper.selectByKey(runId);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getStatus()).isEqualTo("SENT");
        assertThat(messages.get(0).getReason()).isEqualTo("run.started");
    }

    @Test
    void messageIsRolledBackTogetherWithTheTransaction() {
        String runId = runService.createTask("处理 " + newCase()).runId();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.enqueueDispatch(runId, "test");
            throw new IllegalStateException("模拟：同一个事务里后面的操作失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outboxMapper.selectByKey(runId)).isEmpty();
    }

    @Test
    void enqueueOutsideTransactionIsRefused() {
        assertThatThrownBy(() -> outbox.enqueueDispatch("run-x", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同一个事务");
    }

    // ---------------------------------------------------------------- MQ 故障

    @Test
    void whenBrokerIsDownMessageStaysPendingAndRecoveryScanStillRunsTheRun() {
        String po = newCase();
        String runId = runService.createTask("处理 " + po).runId();
        publisher.setMode(SwitchablePublisher.Mode.DOWN);

        RunView run = runService.start(runId);

        // 状态已经改了，消息没投出去，但没丢
        assertThat(run.status()).isEqualTo("RUNNING");
        OutboxMessageEntity message = outboxMapper.selectByKey(runId).get(0);
        assertThat(message.getStatus()).isEqualTo("PENDING");
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getLastError()).contains("连不上 RabbitMQ");

        // MQ 还没恢复：恢复扫描照样把运行跑完（MQ 不是硬依赖）
        recoverAll();
        assertThat(runService.get(runId).status()).isEqualTo("COMPLETED");

        // MQ 恢复，消息补发：消费端发现运行已经结束，跳过，不会再付一次
        publisher.setMode(SwitchablePublisher.Mode.UP);
        makeDue(runId);
        relayAll();
        assertThat(outboxMapper.selectByKey(runId).get(0).getStatus()).isEqualTo("SENT");
        assertThat(gateway.postCount(po)).isEqualTo(1);
    }

    @Test
    void deliveredButNotConfirmedIsRedeliveredAndSkippedByTheConsumer() {
        String po = newCase();
        String runId = runService.createTask("处理 " + po).runId();
        publisher.setMode(SwitchablePublisher.Mode.DELIVER_THEN_FAIL);

        // 消息送达、运行执行完了，但投递器以为失败了
        assertThat(runService.start(runId).status()).isEqualTo("COMPLETED");
        assertThat(outboxMapper.selectByKey(runId).get(0).getStatus()).isEqualTo("PENDING");

        publisher.setMode(SwitchablePublisher.Mode.UP);
        makeDue(runId);
        relayAll();

        OutboxMessageEntity message = outboxMapper.selectByKey(runId).get(0);
        assertThat(message.getStatus()).isEqualTo("SENT");
        assertThat(message.getAttempts()).isEqualTo(2);
        System.out.printf("[确认丢失后重投] 消息投递 %d 次，POST %d 次%n", message.getAttempts(), gateway.postCount(po));
        assertThat(gateway.postCount(po)).isEqualTo(1);
    }

    @Test
    void messageGivesUpAfterMaxAttempts() {
        String runId = runService.createTask("处理 " + newCase()).runId();
        publisher.setMode(SwitchablePublisher.Mode.DOWN);
        runService.start(runId);
        // 已经失败了 19 次，再失败一次就到上限（max-attempts=20）
        jdbc.update("UPDATE outbox_message SET attempts = 19, next_attempt_at = NOW(3) WHERE message_key = ?", runId);

        relay.relayOnce();

        assertThat(outboxMapper.selectByKey(runId).get(0).getStatus()).isEqualTo("DEAD");
        // 放弃投递不等于放弃运行：恢复扫描兜底
        publisher.setMode(SwitchablePublisher.Mode.UP);
        recoverAll();
        assertThat(runService.get(runId).status()).isEqualTo("COMPLETED");
    }

    // ---------------------------------------------------------------- 消费端去重

    @Test
    void consumerSkipsDuplicatesAndRejectsUnknownRuns() {
        String po = newCase();
        String runId = runService.createTask("处理 " + po).runId();
        runService.start(runId);

        // 同一条消息又来了 3 次（发件箱重投 / MQ 重投）
        for (int i = 0; i < 3; i++) {
            assertThat(worker.executeNow(runId, "mq")).isEqualTo(RunWorker.ExecuteOutcome.SKIPPED);
        }
        assertThat(gateway.postCount(po)).isEqualTo(1);
        assertThat(worker.executeNow("run-does-not-exist", "mq")).isEqualTo(RunWorker.ExecuteOutcome.NOT_FOUND);
    }

    // ---------------------------------------------------------------- 多个投递器并发

    /**
     * 8 个投递器同时处理 200 条消息。对照组是"先查出 PENDING、投递、再改 SENT"的朴素写法：
     * 几个投递器查到的是同一批消息，会各投一遍。
     */
    @Test
    void concurrentRelaysPublishEachMessageExactlyOnce() throws Exception {
        int messages = 200;
        int relays = 8;

        String naivePrefix = newKeyPrefix();
        insertMessages(naivePrefix, messages);
        Map<String, AtomicInteger> naive = new ConcurrentHashMap<>();
        runConcurrently(relays, () -> {
            while (true) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT id, message_key FROM outbox_message WHERE status = 'PENDING' AND message_key LIKE ? "
                                + "ORDER BY id LIMIT 50", naivePrefix + "%");
                if (rows.isEmpty()) {
                    return;
                }
                for (Map<String, Object> row : rows) {
                    record(naive, (String) row.get("message_key"));
                    jdbc.update("UPDATE outbox_message SET status = 'SENT' WHERE id = ?", row.get("id"));
                }
            }
        });

        String prefix = newKeyPrefix();
        insertMessages(prefix, messages);
        Map<String, AtomicInteger> ours = new ConcurrentHashMap<>();
        DispatchPublisher recorder = m -> {
            if (m.getMessageKey().startsWith(prefix)) {
                record(ours, m.getMessageKey());
            }
        };
        runConcurrently(relays, () -> {
            while (relay.relayOnce(recorder) > 0) {
                // 一直领到没有为止
            }
        });

        int naiveDuplicates = naive.values().stream().mapToInt(c -> c.get() - 1).sum();
        int ourDuplicates = ours.values().stream().mapToInt(c -> c.get() - 1).sum();
        System.out.printf("[%d 个投递器并发投 %d 条] 朴素写法：投出 %d 次，重复 %d 次；SKIP LOCKED 领取：投出 %d 次，重复 %d 次%n",
                relays, messages, total(naive), naiveDuplicates, total(ours), ourDuplicates);
        assertThat(naiveDuplicates).isGreaterThan(0);
        assertThat(ours).hasSize(messages);
        assertThat(ourDuplicates).isZero();
    }

    // ---------------------------------------------------------------- 辅助

    private String newCase() {
        String po = TestData.randomPo();
        poNumbers.add(po);
        gateway.addCase(po, "Nova Office", 7600, 7600);
        return po;
    }

    private String newKeyPrefix() {
        String prefix = "it-outbox-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        keyPrefixes.add(prefix);
        return prefix;
    }

    private void insertMessages(String prefix, int count) {
        for (int i = 0; i < count; i++) {
            OutboxMessageEntity m = new OutboxMessageEntity();
            m.setTopic("test");
            m.setMessageKey(prefix + i);
            m.setReason("test");
            outboxMapper.enqueue(m);
        }
    }

    private static void record(Map<String, AtomicInteger> counts, String key) {
        counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        // 模拟一次网络投递的耗时，让并发的投递器有机会交错
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int total(Map<String, AtomicInteger> counts) {
        return counts.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    private static void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void makeDue(String runId) {
        jdbc.update("UPDATE outbox_message SET next_attempt_at = NOW(3) WHERE message_key = ?", runId);
    }

    private void relayAll() {
        for (int i = 0; i < 20 && relay.relayOnce() > 0; i++) {
            // 继续
        }
    }

    private void recoverAll() {
        for (int i = 0; i < 20 && worker.recoverOnce() > 0; i++) {
            // 继续
        }
    }
}
