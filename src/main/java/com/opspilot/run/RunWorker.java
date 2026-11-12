package com.opspilot.run;

import com.opspilot.config.JsonCodec;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.event.EventService;
import com.opspilot.store.WorkflowRunMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

/**
 * 后台执行者：先抢执行租约，再把运行交给线程池执行，执行完释放租约。
 *
 * <ul>
 *   <li>本地派发（dispatch.mode=local）：发件箱投递器调 {@link #dispatch}，按 id 抢租约后交给线程池</li>
 *   <li>MQ 派发（dispatch.mode=mq）：消费者在自己的线程里调 {@link #executeNow}</li>
 *   <li>故障恢复：{@link RecoveryScheduler} 定时调 {@link #recoverOnce}，
 *       用 {@code FOR UPDATE SKIP LOCKED} 批量领取"没人在执行"的运行（多实例各领各的）</li>
 * </ul>
 * 同一个运行任何时刻最多一个执行者：租约由条件更新抢占，状态转移又带着租约持有者做 fencing。
 */
@Component
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final WorkflowRunMapper runMapper;
    private final RunExecutor executor;
    private final EventService events;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate claimTx;
    private final String instanceId;
    private final int leaseSeconds;
    private final int batchSize;
    private final int scanGraceSeconds;
    private final Counter recovered;

    public RunWorker(WorkflowRunMapper runMapper, RunExecutor executor, EventService events,
                     @Qualifier("runTaskExecutor") TaskExecutor taskExecutor,
                     PlatformTransactionManager transactionManager, OpsPilotProperties properties,
                     MeterRegistry meterRegistry) {
        this.runMapper = runMapper;
        this.executor = executor;
        this.events = events;
        this.taskExecutor = taskExecutor;
        this.claimTx = new TransactionTemplate(transactionManager);
        // 读已提交：扫描时不加间隙锁，也只锁真正符合条件的行，不挡新建运行和其他实例的状态转移
        this.claimTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.instanceId = resolveInstanceId(properties.worker().instanceId());
        this.leaseSeconds = properties.worker().leaseSeconds();
        this.batchSize = properties.worker().batchSize();
        // 只有 MQ 派发才有"消息在队列里排队"这段时间；本地派发是先抢租约再进线程池，不需要宽限期
        this.scanGraceSeconds = "mq".equals(properties.dispatch().mode())
                ? properties.dispatch().scanGraceSeconds() : 0;
        this.recovered = Counter.builder("opspilot.runs.recovered")
                .description("被恢复扫描接手的运行数")
                .register(meterRegistry);
        log.info("执行者实例 ID：{}", instanceId);
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * 立即执行某个运行。抢不到租约（别人正在执行）就返回 false，不用担心：
     * 正在执行的那个人会把它推进下去；万一他崩溃了，租约过期后恢复扫描会接手。
     */
    public boolean dispatch(String runId) {
        if (runMapper.claimLease(runId, instanceId, leaseSeconds) == 0) {
            return false;
        }
        hand(runId);
        return true;
    }

    /** {@link #executeNow} 的结果。 */
    public enum ExecuteOutcome {
        /** 抢到租约并执行完（推进到下一个稳定状态） */
        EXECUTED,
        /** 没抢到租约：别人正在执行，或者运行已经结束（重复消息） */
        SKIPPED,
        /** 运行不存在（坏消息） */
        NOT_FOUND
    }

    /**
     * 在<b>当前线程</b>抢租约并执行（MQ 消费者用）。和 {@link #dispatch} 的区别：不交给线程池，
     * 执行完才返回，消费者执行完再确认消息；消费者并发数就是执行并发数，消息没被领走之前一直留在 MQ 里。
     *
     * <p>重复消息（投递器重投、MQ 重新投递）在这里被挡住：抢租约是条件更新，
     * 运行已经结束或正在被执行时抢不到，直接跳过。
     */
    public ExecuteOutcome executeNow(String runId, String by) {
        if (runMapper.claimLease(runId, instanceId, leaseSeconds) == 0) {
            return runMapper.selectById(runId) == null ? ExecuteOutcome.NOT_FOUND : ExecuteOutcome.SKIPPED;
        }
        events.append(runId, "run.picked_up", JsonCodec.fields("instance", instanceId, "by", by));
        runClaimed(runId);
        return ExecuteOutcome.EXECUTED;
    }

    /** 恢复扫描一轮：领一批"该有人执行但没人在执行"的运行，交给线程池。返回领到的数量。 */
    public int recoverOnce() {
        List<String> ids = claimBatch(instanceId, batchSize);
        if (!ids.isEmpty()) {
            log.info("恢复扫描接手 {} 个运行：{}", ids.size(), ids);
            recovered.increment(ids.size());
            for (String id : ids) {
                // 时间线上记一笔"谁接手了"，排查时能看出这个运行是被恢复扫描继续的
                events.append(id, "run.picked_up", JsonCodec.fields("instance", instanceId, "by", "recovery_scan"));
                hand(id);
            }
        }
        return ids.size();
    }

    /** 以 owner 的身份批量领取租约（"查出来 + 标记为我的"在一个事务里）。public 是为了测试里模拟多个实例。 */
    public List<String> claimBatch(String owner, int limit) {
        List<String> ids = claimTx.execute(s -> {
            List<String> locked = runMapper.lockClaimable(limit, scanGraceSeconds);
            if (!locked.isEmpty()) {
                runMapper.leaseLocked(locked, owner, leaseSeconds);
            }
            return locked;
        });
        return ids == null ? List.of() : ids;
    }

    /** 启动时调用：释放上一次进程（同一个实例 ID）留下的租约，不用干等它们过期。 */
    public int releaseOwnLeases() {
        return runMapper.releaseAllOwnedBy(instanceId);
    }

    private void hand(String runId) {
        try {
            taskExecutor.execute(() -> runClaimed(runId));
        } catch (TaskRejectedException e) {
            // 线程池满了：马上释放租约，下一轮恢复扫描再领
            runMapper.releaseLease(runId, instanceId);
            log.warn("执行线程池已满，运行 {} 稍后由恢复扫描执行", runId);
        }
    }

    private void runClaimed(String runId) {
        try {
            executor.execute(runId, instanceId);
        } catch (RuntimeException e) {
            log.error("运行 {} 执行异常", runId, e);
        } finally {
            runMapper.releaseLease(runId, instanceId);
        }
    }

    private static String resolveInstanceId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "instance-" + UUID.randomUUID().toString().substring(0, 8);
            }
        }
        return host;
    }
}
