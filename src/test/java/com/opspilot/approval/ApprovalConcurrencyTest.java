package com.opspilot.approval;

import com.opspilot.api.ConflictException;
import com.opspilot.run.EventView;
import com.opspilot.run.RunService;
import com.opspilot.run.RunView;
import com.opspilot.support.FakeGateway;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 多个审批人同时处理同一张审批单。 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class ApprovalConcurrencyTest {

    private static final int APPROVERS = 10;

    @Autowired
    private RunService runService;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private FakeGateway gateway;
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
    void concurrentApprovalsResolveOnceAndPayOnce() throws Exception {
        Pending p = newPendingApproval();

        Map<String, Integer> outcomes = decideConcurrently(p.approvalId(), i -> "approve");

        RunView run = runService.get(p.runId());
        long approvedEvents = events(p.runId()).stream().filter("approval.approved"::equals).count();
        System.out.printf("[并发批准] %d 人同时批准：%s；POST %d 次；approval.approved 事件 %d 条%n",
                APPROVERS, outcomes, gateway.postCount(p.po()), approvedEvents);
        assertThat(outcomes).containsEntry("RESOLVED", 1).containsEntry("ALREADY_RESOLVED", APPROVERS - 1);
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(gateway.postCount(p.po())).isEqualTo(1);
        assertThat(approvedEvents).isEqualTo(1);
    }

    @Test
    void conflictingDecisionsHaveExactlyOneWinner() throws Exception {
        Pending p = newPendingApproval();

        // 一半批准、一半驳回
        Map<String, Integer> outcomes = decideConcurrently(p.approvalId(), i -> i % 2 == 0 ? "approve" : "reject");

        RunView run = runService.get(p.runId());
        System.out.printf("[批准/驳回同时到达] %s；最终运行状态 %s；POST %d 次%n",
                outcomes, run.status(), gateway.postCount(p.po()));
        // 赢的一方：1 个 RESOLVED + 4 个 ALREADY_RESOLVED；输的一方 5 个全部 409
        assertThat(outcomes).containsEntry("RESOLVED", 1)
                .containsEntry("ALREADY_RESOLVED", APPROVERS / 2 - 1)
                .containsEntry("CONFLICT", APPROVERS / 2);
        if ("COMPLETED".equals(run.status())) {
            assertThat(gateway.postCount(p.po())).isEqualTo(1);
        } else {
            assertThat(run.status()).isEqualTo("REJECTED");
            assertThat(gateway.postCount(p.po())).isZero();
        }
    }

    // ----------------------------------------------------------------

    private record Pending(String po, String runId, String approvalId) {
    }

    private Pending newPendingApproval() {
        String po = TestData.randomPo();
        poNumbers.add(po);
        gateway.addCase(po, "ABC Technology", 12800, 12800);
        RunView run = runService.start(runService.createTask("处理 " + po).runId());
        assertThat(run.status()).isEqualTo("WAITING_APPROVAL");
        return new Pending(po, run.id(), (String) run.data().get("approval_id"));
    }

    interface DecisionFor {
        String of(int approverIndex);
    }

    private Map<String, Integer> decideConcurrently(String approvalId, DecisionFor decisionFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(APPROVERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < APPROVERS; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return approvalService.decide(approvalId, decisionFor.of(index), "approver-" + index)
                                .outcome().name();
                    } catch (ConflictException e) {
                        return "CONFLICT";
                    }
                }));
            }
            start.countDown();
            Map<String, Integer> counts = new TreeMap<>();
            for (Future<String> f : futures) {
                counts.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
            }
            return counts;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String> events(String runId) {
        return runService.events(runId, 0).stream().map(EventView::eventType).toList();
    }
}
