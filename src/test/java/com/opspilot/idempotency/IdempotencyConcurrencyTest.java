package com.opspilot.idempotency;

import com.opspilot.store.IdempotencyRecordEntity;
import com.opspilot.support.ItConfig;
import com.opspilot.support.MySqlProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多个执行者同时抢同一笔付款的幂等记录。对比：
 * <ul>
 *   <li>"先查租约是否过期，再在 Java 里判断，再更新"——多个执行者都以为自己接手成功（会重复付款）</li>
 *   <li>条件 UPDATE（判断和修改在一条 SQL 里）——只有一个成功</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class IdempotencyConcurrencyTest {

    private static final int WORKERS = 20;

    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> keys = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        MySqlProps.register(registry);
    }

    @AfterEach
    void cleanUp() {
        keys.forEach(k -> jdbc.update("DELETE FROM idempotency_record WHERE idem_key = ?", k));
        keys.clear();
    }

    @Test
    void concurrentFirstClaimsLetExactlyOneWorkerSubmit() throws Exception {
        String key = newKey();

        Map<ClaimOutcome, AtomicInteger> outcomes = runConcurrently(i ->
                () -> idempotency.claim(key, "submit_payment", "run-" + i).outcome());

        System.out.printf("[首次抢占] %d 个执行者：%s%n", WORKERS, outcomes);
        assertThat(count(outcomes, ClaimOutcome.ACQUIRED)).isEqualTo(1);
        assertThat(count(outcomes, ClaimOutcome.OCCUPIED)).isEqualTo(WORKERS - 1);
    }

    @Test
    void naiveCheckThenUpdateLetsSeveralWorkersReclaimTheSameExpiredLease() throws Exception {
        String key = newExpiredLease();
        CyclicBarrier allChecked = new CyclicBarrier(WORKERS);

        Map<Boolean, AtomicInteger> believedReclaimed = runConcurrently(i -> () -> {
            // 1) 先查：租约过期了吗？（用数据库时间判断，和正式实现一致）
            Boolean expired = jdbc.queryForObject(
                    "SELECT status = 'PENDING' AND lease_expires_at <= NOW(3) FROM idempotency_record WHERE idem_key = ?",
                    Boolean.class, key);
            // 让所有执行者都查完再一起往下走，稳定复现最坏的交错
            allChecked.await(10, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(expired)) {
                return false;
            }
            // 2) 再改：WHERE 里只有主键，没有带上"仍然过期"这个条件
            jdbc.update("UPDATE idempotency_record SET owner_run_id = ?, attempt = attempt + 1, "
                    + "lease_expires_at = DATE_ADD(NOW(3), INTERVAL 30 SECOND) WHERE idem_key = ?", "run-" + i, key);
            return true;
        });

        System.out.printf("[先查后改] %d 个执行者里有 %d 个以为自己接手成功（每个都会去提交一次付款）%n",
                WORKERS, count(believedReclaimed, true));
        assertThat(count(believedReclaimed, true)).isGreaterThan(1);
    }

    @Test
    void conditionalUpdateLetsExactlyOneWorkerReclaimAnExpiredLease() throws Exception {
        String key = newExpiredLease();

        Map<ClaimOutcome, AtomicInteger> outcomes = runConcurrently(i ->
                () -> idempotency.claim(key, "submit_payment", "run-" + i).outcome());

        IdempotencyRecordEntity record = idempotency.find(key);
        System.out.printf("[条件更新] %d 个执行者：%s，attempt=%d%n", WORKERS, outcomes, record.getAttempt());
        assertThat(count(outcomes, ClaimOutcome.RECLAIMED)).isEqualTo(1);
        assertThat(count(outcomes, ClaimOutcome.OCCUPIED)).isEqualTo(WORKERS - 1);
        assertThat(record.getAttempt()).isEqualTo(2);
        assertThat(record.getOwnerRunId()).isNotEqualTo("run-original");
    }

    @Test
    void completedRecordIsReturnedInsteadOfSubmittingAgain() {
        String key = newKey();
        assertThat(idempotency.claim(key, "submit_payment", "run-a").outcome()).isEqualTo(ClaimOutcome.ACQUIRED);
        assertThat(idempotency.complete(key, "{\"id\":\"PAY-1\"}")).isTrue();
        // 第二次完成不生效
        assertThat(idempotency.complete(key, "{\"id\":\"PAY-2\"}")).isFalse();

        ClaimResult again = idempotency.claim(key, "submit_payment", "run-b");
        assertThat(again.outcome()).isEqualTo(ClaimOutcome.COMPLETED);
        assertThat(again.record().getResult()).contains("PAY-1");
        // 完成的记录即使"过期"也不能被回收
        assertThat(jdbc.queryForObject("SELECT lease_expires_at IS NULL FROM idempotency_record WHERE idem_key = ?",
                Boolean.class, key)).isTrue();
    }

    @Test
    void onlyTheOwnerCanReleaseAPendingRecord() {
        String key = newKey();
        idempotency.claim(key, "submit_payment", "run-a");
        assertThat(idempotency.release(key, "run-b")).isFalse();
        assertThat(idempotency.release(key, "run-a")).isTrue();
        // 释放后可以立即重新抢到
        assertThat(idempotency.claim(key, "submit_payment", "run-b").outcome()).isEqualTo(ClaimOutcome.ACQUIRED);
    }

    // ------------------------------------------------------------------

    private String newKey() {
        String key = "it-idem:" + UUID.randomUUID();
        keys.add(key);
        return key;
    }

    /** 一条 PENDING 且租约已过期的记录：模拟持有者提交到一半崩溃了。 */
    private String newExpiredLease() {
        String key = newKey();
        assertThat(idempotency.claim(key, "submit_payment", "run-original").outcome()).isEqualTo(ClaimOutcome.ACQUIRED);
        jdbc.update("UPDATE idempotency_record SET lease_expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE idem_key = ?", key);
        return key;
    }

    /** WORKERS 个线程同时开始执行，统计每种结果出现的次数。 */
    private static <T> Map<T, AtomicInteger> runConcurrently(IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                Callable<T> c = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return c.call();
                }));
            }
            start.countDown();
            Map<T, AtomicInteger> counts = new ConcurrentHashMap<>();
            for (Future<T> f : futures) {
                counts.computeIfAbsent(f.get(30, TimeUnit.SECONDS), k -> new AtomicInteger()).incrementAndGet();
            }
            return counts;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> int count(Map<T, AtomicInteger> counts, T key) {
        AtomicInteger n = counts.get(key);
        return n == null ? 0 : n.get();
    }
}
