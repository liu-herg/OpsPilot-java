package com.opspilot.run;

import com.opspilot.store.WorkflowRunMapper;
import com.opspilot.support.ItConfig;
import com.opspilot.support.MySqlProps;
import com.opspilot.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多个实例同时做恢复扫描，领取同一批"没人在执行"的运行。对比：
 * <ul>
 *   <li>先普通 SELECT 查出候选，再 UPDATE 标记为自己的——同一个运行被多个实例领走（会被执行多次）</li>
 *   <li>{@code SELECT ... FOR UPDATE SKIP LOCKED} + 同事务标记——每个运行只被一个实例领走，实例之间不互相等待</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class MultiInstanceClaimTest {

    private static final int INSTANCES = 4;
    private static final int RUNS = 40;

    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private WorkflowRunMapper runMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> poNumbers = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        MySqlProps.register(registry);
    }

    @AfterEach
    void cleanUp() {
        for (int i = 0; i < INSTANCES; i++) {
            runMapper.releaseAllOwnedBy(owner(i));
        }
        TestData.cleanup(jdbc, poNumbers);
        poNumbers.clear();
    }

    @Test
    void naiveSelectThenUpdateLetsSeveralInstancesClaimTheSameRun() throws Exception {
        List<String> runIds = abandonedRuns();
        String in = String.join(",", Collections.nCopies(runIds.size(), "?"));
        CyclicBarrier allSelected = new CyclicBarrier(INSTANCES);
        Map<String, List<String>> claimedBy = new ConcurrentHashMap<>();

        runConcurrently(i -> {
            // 1) 普通查询：谁需要被接手？（只看本测试造的运行）
            List<String> candidates = jdbc.queryForList("SELECT id FROM workflow_run WHERE id IN (" + in + ") "
                    + "AND status = 'RUNNING' AND (lease_owner IS NULL OR lease_expires_at <= NOW(3))",
                    String.class, runIds.toArray());
            allSelected.await(10, TimeUnit.SECONDS);
            // 2) 标记为自己的（WHERE 里没有再检查"仍然没人持有"）
            for (String id : candidates) {
                jdbc.update("UPDATE workflow_run SET lease_owner = ?, "
                        + "lease_expires_at = DATE_ADD(NOW(3), INTERVAL 60 SECOND) WHERE id = ?", owner(i), id);
                claimedBy.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(owner(i));
            }
        });

        long duplicated = claimedBy.values().stream().filter(owners -> owners.size() > 1).count();
        System.out.printf("[先查后改] %d 个实例同时扫描 %d 个待恢复运行：%d 个运行被多个实例同时领走（会被重复执行）%n",
                INSTANCES, RUNS, duplicated);
        assertThat(duplicated).isGreaterThan(0);
    }

    @Test
    void skipLockedGivesEveryRunToExactlyOneInstance() throws Exception {
        List<String> runIds = abandonedRuns();
        Set<String> ours = Set.copyOf(runIds);
        Map<String, List<String>> claimedBy = new ConcurrentHashMap<>();

        long startedAt = System.nanoTime();
        runConcurrently(i -> {
            // 每个实例反复领一小批，直到领不到为止
            List<String> batch;
            do {
                batch = worker.claimBatch(owner(i), 5);
                for (String id : batch) {
                    claimedBy.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(owner(i));
                }
            } while (!batch.isEmpty());
        });
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        Map<String, List<String>> oursClaimed = claimedBy.entrySet().stream()
                .filter(e -> ours.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, Long> perInstance = new TreeMap<>(oursClaimed.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(o -> o, Collectors.counting())));
        long duplicated = oursClaimed.values().stream().filter(owners -> owners.size() > 1).count();
        System.out.printf("[SKIP LOCKED] %d 个实例同时扫描 %d 个待恢复运行：全部领完 %d 个，重复 %d 个，各实例分到 %s，耗时 %d ms%n",
                INSTANCES, RUNS, oursClaimed.size(), duplicated, perInstance, elapsedMs);
        assertThat(oursClaimed.keySet()).containsExactlyInAnyOrderElementsOf(runIds);
        assertThat(duplicated).isZero();
        // 数据库里的租约持有者和领取记录一致
        for (String id : runIds) {
            assertThat(runService.get(id).leaseOwner()).isEqualTo(oursClaimed.get(id).get(0));
        }
    }

    // ----------------------------------------------------------------

    /** RUNS 个"执行到一半、执行者已死"的运行。 */
    private List<String> abandonedRuns() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            String po = TestData.randomPo();
            poNumbers.add(po);
            ids.add(runService.createTask("处理 " + po).runId());
        }
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.update("UPDATE workflow_run SET status = 'RUNNING', lease_owner = 'dead-instance', "
                + "lease_expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id IN (" + in + ")", ids.toArray());
        return ids;
    }

    private static String owner(int i) {
        return "it-instance-" + i;
    }

    interface Body {
        void run(int index) throws Exception;
    }

    private static void runConcurrently(Body body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
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
