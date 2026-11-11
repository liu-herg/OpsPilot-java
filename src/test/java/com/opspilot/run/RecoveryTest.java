package com.opspilot.run;

import com.opspilot.store.WorkflowRunMapper;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障恢复：用 SQL 把运行改成"执行到一半、执行者已经死了"的样子（租约过期），
 * 然后跑一轮恢复扫描，看能不能正确接手，并且不重复付款。
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class RecoveryTest {

    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private RunStore runStore;
    @Autowired
    private WorkflowRunMapper runMapper;
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
    void runAbandonedWhileRunningIsPickedUpAndCompleted() {
        String po = newCase(7600);
        String runId = runService.createTask("处理 " + po).runId();
        // 模拟：已经点了启动（RUNNING），执行者拿到租约后进程崩溃，一步都没做
        jdbc.update("UPDATE workflow_run SET status = 'RUNNING', started_at = NOW(3), lease_owner = 'dead-instance', "
                + "lease_expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", runId);

        recoverAll();

        RunView run = runService.get(runId);
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.leaseOwner()).isNull();
        assertThat(gateway.postCount(po)).isEqualTo(1);
    }

    @Test
    void liveLeaseIsNotStolen() {
        String po = newCase(7600);
        String runId = runService.createTask("处理 " + po).runId();
        // 另一个实例正在执行，租约还有 60 秒
        jdbc.update("UPDATE workflow_run SET status = 'RUNNING', lease_owner = 'alive-instance', "
                + "lease_expires_at = DATE_ADD(NOW(3), INTERVAL 60 SECOND) WHERE id = ?", runId);

        recoverAll();
        assertThat(worker.dispatch(runId)).isFalse();

        assertThat(runService.get(runId).status()).isEqualTo("RUNNING");
        assertThat(runService.get(runId).leaseOwner()).isEqualTo("alive-instance");
        assertThat(gateway.postCount(po)).isZero();
    }

    @Test
    void crashAfterPaymentWasPostedIsReconciledWithoutPayingAgain() {
        String po = newCase(7600);
        // 财务系统建好了付款，但我们没记下结果（响应丢失 / 进程崩溃在记录之前）
        gateway.failNextSubmit(po, FakeGateway.Failure.RESPONSE_LOST);
        String runId = runService.start(runService.createTask("处理 " + po).runId()).id();
        assertThat(runService.get(runId).status()).isEqualTo("READY_TO_SUBMIT");
        // 模拟崩溃时的样子：租约还挂在死掉的实例上（已过期），也到了重试时间
        jdbc.update("UPDATE workflow_run SET retry_at = NULL, lease_owner = 'dead-instance', "
                + "lease_expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", runId);

        recoverAll();

        assertThat(runService.get(runId).status()).isEqualTo("COMPLETED");
        assertThat(eventTypes(runId)).contains("payment.reconciled");
        System.out.printf("[付款后崩溃] 恢复后：POST %d 次，付款 %d 笔%n", gateway.postCount(po), gateway.paymentCount(po));
        assertThat(gateway.postCount(po)).isEqualTo(1);
        assertThat(gateway.paymentCount(po)).isEqualTo(1);
    }

    @Test
    void upstreamOutageIsRetriedWithBackoffInsteadOfFailing() {
        String po = newCase(7600);
        gateway.failReads(po, 2);

        String runId = runService.start(runService.createTask("处理 " + po).runId()).id();
        RunView afterFirst = runService.get(runId);
        assertThat(afterFirst.status()).isEqualTo("RUNNING");
        assertThat(afterFirst.retryCount()).isEqualTo(1);
        assertThat(afterFirst.retryAt()).isNotNull();
        assertThat(afterFirst.leaseOwner()).isNull();

        // 还没到重试时间：恢复扫描不碰它
        recoverAll();
        assertThat(runService.get(runId).retryCount()).isEqualTo(1);

        for (int i = 0; i < 2; i++) {
            makeRetryDue(runId);
            recoverAll();
        }
        RunView done = runService.get(runId);
        assertThat(done.status()).isEqualTo("COMPLETED");
        // 进入 READY_TO_SUBMIT 时重试计数清零
        assertThat(done.retryCount()).isZero();
        assertThat(eventTypes(runId).stream().filter("step.retry_scheduled"::equals).count()).isEqualTo(2);
    }

    @Test
    void readStepGivesUpAfterMaxRetries() {
        String po = newCase(7600);
        gateway.failReads(po, 100);
        String runId = runService.start(runService.createTask("处理 " + po).runId()).id();

        for (int i = 0; i < 10 && !"FAILED".equals(runService.get(runId).status()); i++) {
            makeRetryDue(runId);
            recoverAll();
        }
        RunView run = runService.get(runId);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorType()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(gateway.postCount(po)).isZero();
    }

    @Test
    void zombieExecutorCannotChangeStateAfterItsLeaseWasTakenOver() {
        String po = newCase(7600);
        String runId = runService.createTask("处理 " + po).runId();
        // A 拿着租约执行，卡住了（比如长时间 GC），租约过期
        jdbc.update("UPDATE workflow_run SET status = 'RUNNING', lease_owner = 'instance-A', "
                + "lease_expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", runId);
        // B 接手
        assertThat(runMapper.claimLease(runId, "instance-B", 60)).isEqualTo(1);

        // A 醒过来继续往下走：状态还是 RUNNING，但租约已经不是它的，改不动
        boolean zombie = runStore.transition(runId, RunStatus.RUNNING,
                RunUpdate.to(RunStatus.WAITING_APPROVAL).step("approval").ownedBy("instance-A"));
        boolean owner = runStore.transition(runId, RunStatus.RUNNING,
                RunUpdate.to(RunStatus.RUNNING).step("fetch_order").ownedBy("instance-B"));

        System.out.printf("[fencing] 租约被接手后：旧执行者转移状态 %s，新执行者 %s%n",
                zombie ? "成功" : "被拒绝", owner ? "成功" : "被拒绝");
        assertThat(zombie).isFalse();
        assertThat(owner).isTrue();
        assertThat(runService.get(runId).status()).isEqualTo("RUNNING");
        runMapper.releaseLease(runId, "instance-B");
    }

    @Test
    void startupReleasesLeasesLeftByThePreviousProcessOfTheSameInstance() {
        String po = newCase(7600);
        String runId = runService.createTask("处理 " + po).runId();
        // 本实例上一次进程留下的租约（还没过期）：重启后不用等 60 秒
        jdbc.update("UPDATE workflow_run SET status = 'RUNNING', lease_owner = ?, "
                + "lease_expires_at = DATE_ADD(NOW(3), INTERVAL 60 SECOND) WHERE id = ?", worker.instanceId(), runId);

        assertThat(worker.releaseOwnLeases()).isGreaterThanOrEqualTo(1);
        recoverAll();

        assertThat(runService.get(runId).status()).isEqualTo("COMPLETED");
    }

    // ----------------------------------------------------------------

    private String newCase(long amount) {
        String po = TestData.randomPo();
        poNumbers.add(po);
        gateway.addCase(po, "Nova Office", amount, amount);
        return po;
    }

    private void makeRetryDue(String runId) {
        jdbc.update("UPDATE workflow_run SET retry_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", runId);
    }

    /** 同步执行器下，recoverOnce 返回时领到的运行都已经执行完。库里可能还有别的待恢复运行，多扫几轮。 */
    private void recoverAll() {
        for (int i = 0; i < 20 && worker.recoverOnce() > 0; i++) {
            // 继续
        }
    }

    private List<String> eventTypes(String runId) {
        return runService.events(runId, 0).stream().map(EventView::eventType).toList();
    }
}
