package com.opspilot.run;

import com.opspilot.api.ConflictException;
import com.opspilot.approval.ApprovalService;
import com.opspilot.approval.DecisionOutcome;
import com.opspilot.idempotency.IdempotencyService;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 完整流程（真实 MySQL + 假业务系统）：三个演示场景，以及提交付款时的各种故障。
 * 假财务系统本身不去重，所以"只付了一笔"完全是控制面保证的。
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class PaymentFlowTest {

    @Autowired
    private RunService runService;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private IdempotencyService idempotency;
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
        gateway.setSubmitDelayMs(0);
        TestData.cleanup(jdbc, poNumbers);
        poNumbers.clear();
    }

    // ---------------------------------------------------------------- 三个演示场景

    @Test
    void smallAmountIsPaidWithoutApproval() {
        String po = newCase(7600, 7600);
        RunView run = runService.start(runService.createTask("帮我处理 " + po + " 的付款").runId());

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.data()).containsKey("payment");
        assertThat(gateway.postCount(po)).isEqualTo(1);
        assertThat(eventTypes(run.id())).containsSubsequence(
                "run.created", "run.started", "step.started", "step.completed", "policy.auto_approved",
                "submission.started", "payment.submitted", "side_effect.verified", "run.completed");
        assertContiguous(run.id());
        assertThat(idempotency.find(PaymentSubmitter.idempotencyKey(po, invoiceOf(po))).getStatus())
                .isEqualTo("COMPLETED");
    }

    @Test
    void largeAmountWaitsForApprovalThenPays() {
        String po = newCase(12800, 12800);
        RunView run = runService.start(runService.createTask("帮我处理 " + po + " 的付款").runId());
        assertThat(run.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(gateway.postCount(po)).isZero();

        String approvalId = (String) run.data().get("approval_id");
        var result = approvalService.decide(approvalId, "approve", "finance-lead");
        assertThat(result.outcome()).isEqualTo(DecisionOutcome.RESOLVED);
        assertThat(result.run().status()).isEqualTo("COMPLETED");
        assertThat(result.approval().decisionActor()).isEqualTo("finance-lead");
        assertThat(gateway.postCount(po)).isEqualTo(1);

        // 重复提交同样的决定：幂等，不会再付一次
        var again = approvalService.decide(approvalId, "approve", "finance-lead");
        assertThat(again.outcome()).isEqualTo(DecisionOutcome.ALREADY_RESOLVED);
        assertThat(gateway.postCount(po)).isEqualTo(1);
        // 相反的决定：409
        assertThatThrownBy(() -> approvalService.decide(approvalId, "reject", "someone-else"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectedApprovalNeverPays() {
        String po = newCase(12800, 12800);
        RunView run = runService.start(runService.createTask("处理 " + po).runId());
        var result = approvalService.decide((String) run.data().get("approval_id"), "reject", "finance-lead");

        assertThat(result.run().status()).isEqualTo("REJECTED");
        assertThat(result.run().finishedAt()).isNotNull();
        assertThat(gateway.postCount(po)).isZero();
    }

    @Test
    void amountMismatchFailsValidationBeforeAnySideEffect() {
        String po = newCase(7600, 7500);
        RunView run = runService.start(runService.createTask("处理 " + po).runId());

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorType()).isEqualTo("BUSINESS_VALIDATION_FAILED");
        assertThat(run.data()).containsKey("mismatches");
        assertThat(gateway.postCount(po)).isZero();
    }

    @Test
    void unknownPurchaseOrderFails() {
        String po = TestData.randomPo();
        poNumbers.add(po);
        RunView run = runService.start(runService.createTask("处理 " + po).runId());
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorType()).isEqualTo("NOT_FOUND");
    }

    @Test
    void runCanOnlyBeStartedOnce() {
        String po = newCase(7600, 7600);
        String runId = runService.createTask("处理 " + po).runId();
        runService.start(runId);
        assertThatThrownBy(() -> runService.start(runId)).isInstanceOf(ConflictException.class);
        assertThat(gateway.postCount(po)).isEqualTo(1);
    }

    @Test
    void requestWithoutPoNumberIsRejected() {
        assertThatThrownBy(() -> runService.createTask("帮我付个款")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 提交故障

    @Test
    void lostResponseIsReconciledInsteadOfPayingTwice() {
        String po = newCase(7600, 7600);
        gateway.failNextSubmit(po, FakeGateway.Failure.RESPONSE_LOST);

        RunView run = runService.start(runService.createTask("处理 " + po).runId());
        // 财务系统其实已经建好了付款，但我们没收到响应：不能当成失败，也不能当成成功
        assertThat(run.status()).isEqualTo("READY_TO_SUBMIT");
        assertThat(eventTypes(run.id())).contains("submission.ambiguous");
        assertThat(idempotency.find(PaymentSubmitter.idempotencyKey(po, invoiceOf(po))).getStatus())
                .isEqualTo("PENDING");

        RunView resumed = runService.resume(run.id());
        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(eventTypes(run.id())).contains("payment.reconciled");
        System.out.printf("[响应丢失] 重试后：POST %d 次，财务系统付款 %d 笔%n",
                gateway.postCount(po), gateway.paymentCount(po));
        assertThat(gateway.postCount(po)).isEqualTo(1);
        assertThat(gateway.paymentCount(po)).isEqualTo(1);
    }

    @Test
    void connectionFailureReleasesTheKeySoRetryCanSubmitImmediately() {
        String po = newCase(7600, 7600);
        gateway.failNextSubmit(po, FakeGateway.Failure.NOT_SENT);

        RunView run = runService.start(runService.createTask("处理 " + po).runId());
        assertThat(run.status()).isEqualTo("READY_TO_SUBMIT");
        assertThat(eventTypes(run.id())).contains("submission.failed");
        // 请求肯定没发出去：记录被释放，不用等租约过期
        assertThat(idempotency.find(PaymentSubmitter.idempotencyKey(po, invoiceOf(po)))).isNull();

        assertThat(runService.resume(run.id()).status()).isEqualTo("COMPLETED");
        assertThat(gateway.postCount(po)).isEqualTo(2);
        assertThat(gateway.paymentCount(po)).isEqualTo(1);
    }

    @Test
    void explicitRejectionFailsTheRun() {
        String po = newCase(7600, 7600);
        gateway.failNextSubmit(po, FakeGateway.Failure.REJECTED);

        RunView run = runService.start(runService.createTask("处理 " + po).runId());
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorType()).isEqualTo("PAYMENT_REJECTED");
        assertThat(idempotency.find(PaymentSubmitter.idempotencyKey(po, invoiceOf(po)))).isNull();
    }

    @Test
    void concurrentRunsForTheSamePurchaseOrderPayOnlyOnce() throws Exception {
        int runs = 10;
        String po = newCase(7600, 7600);
        gateway.setSubmitDelayMs(300);
        List<String> runIds = IntStream.range(0, runs)
                .mapToObj(i -> runService.createTask("处理 " + po + "（第 " + i + " 次提交）").runId())
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RunView>> futures = new ArrayList<>();
        try {
            for (String id : runIds) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return runService.start(id);
                }));
            }
            start.countDown();
            for (Future<RunView> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 被推迟的运行（当时别人正在提交）再继续一次
        for (String id : runIds) {
            if ("READY_TO_SUBMIT".equals(runService.get(id).status())) {
                runService.resume(id);
            }
        }

        Map<String, Integer> howCompleted = new TreeMap<>();
        for (String id : runIds) {
            RunView view = runService.get(id);
            assertThat(view.status()).isEqualTo("COMPLETED");
            eventTypes(id).stream()
                    .filter(t -> t.startsWith("payment."))
                    .forEach(t -> howCompleted.merge(t, 1, Integer::sum));
        }
        System.out.printf("[同一 PO 并发 %d 个运行] POST %d 次，付款 %d 笔，完成方式：%s%n",
                runs, gateway.postCount(po), gateway.paymentCount(po), howCompleted);
        assertThat(gateway.postCount(po)).isEqualTo(1);
        assertThat(gateway.paymentCount(po)).isEqualTo(1);
    }

    // ----------------------------------------------------------------

    private String newCase(long orderAmount, long invoiceAmount) {
        String po = TestData.randomPo();
        poNumbers.add(po);
        gateway.addCase(po, "Nova Office", orderAmount, invoiceAmount);
        return po;
    }

    private static String invoiceOf(String po) {
        return "INV-" + po.substring(3);
    }

    private List<String> eventTypes(String runId) {
        return runService.events(runId, 0).stream().map(EventView::eventType).toList();
    }

    private void assertContiguous(String runId) {
        List<Integer> sequences = runService.events(runId, 0).stream().map(EventView::sequence).toList();
        assertThat(sequences).containsExactlyElementsOf(IntStream.rangeClosed(1, sequences.size()).boxed().toList());
    }
}
