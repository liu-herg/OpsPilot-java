package com.opspilot.event;

import com.opspilot.run.RunService;
import com.opspilot.store.RunEventEntity;
import com.opspilot.support.ItConfig;
import com.opspilot.support.MySqlProps;
import com.opspilot.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 同一个运行并发追加事件：序号必须连续、不重复。 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class EventSequenceTest {

    private static final int THREADS = 10;
    private static final int EVENTS_PER_THREAD = 20;

    @Autowired
    private EventService events;
    @Autowired
    private RunService runService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> poNumbers = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        MySqlProps.register(registry);
    }

    @AfterEach
    void cleanUp() {
        TestData.cleanup(jdbc, poNumbers);
        poNumbers.clear();
    }

    @Test
    void naiveMaxPlusOneProducesDuplicateSequences() throws Exception {
        String runId = newRun();
        CyclicBarrier allRead = new CyclicBarrier(THREADS);
        AtomicInteger duplicates = new AtomicInteger();

        runConcurrently(THREADS, i -> {
            Integer next = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sequence), 0) + 1 FROM run_event WHERE run_id = ?", Integer.class, runId);
            allRead.await(10, TimeUnit.SECONDS);
            try {
                jdbc.update("INSERT INTO run_event (run_id, sequence, event_type, payload, created_at) "
                        + "VALUES (?, ?, 'naive', '{}', NOW(3))", runId, next);
            } catch (DuplicateKeyException e) {
                duplicates.incrementAndGet();
            }
        });

        System.out.printf("[MAX+1] %d 个线程同时追加，%d 个拿到了重复序号（被唯一索引拦下，事件丢失）%n",
                THREADS, duplicates.get());
        assertThat(duplicates.get()).isEqualTo(THREADS - 1);
    }

    @Test
    void atomicCounterGivesContiguousUniqueSequences() throws Exception {
        String runId = newRun();   // 创建时已经有 1 条 run.created

        runConcurrently(THREADS, i -> {
            for (int n = 0; n < EVENTS_PER_THREAD; n++) {
                events.append(runId, "it.concurrent", Map.of("thread", i, "n", n));
            }
        });

        List<Integer> sequences = jdbc.queryForList(
                "SELECT sequence FROM run_event WHERE run_id = ? ORDER BY sequence", Integer.class, runId);
        int expected = 1 + THREADS * EVENTS_PER_THREAD;
        System.out.printf("[原子计数] %d 个线程 × %d 条，共 %d 条，序号 1..%d 连续无重复%n",
                THREADS, EVENTS_PER_THREAD, sequences.size(), sequences.get(sequences.size() - 1));
        assertThat(sequences).containsExactlyElementsOf(IntStream.rangeClosed(1, expected).boxed().toList());
        assertThat(jdbc.queryForObject("SELECT event_sequence FROM workflow_run WHERE id = ?", Integer.class, runId))
                .isEqualTo(expected);
    }

    @Test
    void afterParameterReturnsOnlyNewerEvents() {
        String runId = newRun();
        events.append(runId, "a", Map.of());
        events.append(runId, "b", Map.of());
        assertThat(events.list(runId, 2).stream().map(RunEventEntity::getEventType).toList()).containsExactly("b");
    }

    private String newRun() {
        String po = TestData.randomPo();
        poNumbers.add(po);
        return runService.createTask("处理 " + po + " 的付款").runId();
    }

    interface Body {
        void run(int index) throws Exception;
    }

    private static void runConcurrently(int threads, Body body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    body.run(index);
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
}
