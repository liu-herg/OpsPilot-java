package com.opspilot.run;

import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.config.Ids;
import com.opspilot.config.JsonCodec;
import com.opspilot.dispatch.OutboxRelay;
import com.opspilot.dispatch.OutboxService;
import com.opspilot.event.EventService;
import com.opspilot.store.TaskEntity;
import com.opspilot.store.TaskMapper;
import com.opspilot.store.WorkflowRunEntity;
import com.opspilot.store.WorkflowRunMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.opspilot.config.JsonCodec.fields;

/**
 * 运行的对外操作：创建、启动、继续、查询。真正的执行在后台（{@link RunWorker} → {@link RunExecutor}），
 * 接口只负责把状态改对、把"要派发"写进发件箱（同一个事务），立刻返回。
 * 事务提交后触发投递器，由它交给本地线程池或 RabbitMQ。
 */
@Service
public class RunService {

    /** PO-10001（演示数据）或 PO-20261112-0001（系统生成的订单号） */
    private static final Pattern PO_PATTERN = Pattern.compile("PO-\\d+(-\\d+)?");
    static final String TASK_TYPE = "invoice_payment";

    private final TaskMapper taskMapper;
    private final WorkflowRunMapper runMapper;
    private final RunStore runStore;
    private final EventService events;
    private final OutboxService outbox;
    private final OutboxRelay relay;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final ObjectProvider<RunTargetResolver> targets;

    public RunService(TaskMapper taskMapper, WorkflowRunMapper runMapper, RunStore runStore, EventService events,
                      OutboxService outbox, OutboxRelay relay, TransactionTemplate tx, JsonCodec json,
                      ObjectProvider<RunTargetResolver> targets) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.runStore = runStore;
        this.events = events;
        this.outbox = outbox;
        this.relay = relay;
        this.tx = tx;
        this.json = json;
        this.targets = targets;
    }

    /**
     * 快捷入口："帮我处理 PO-10001 的付款"。P4 起找到这张采购订单最早的一张待付款付款单，为它创建运行。
     */
    public TaskCreated createTask(String request) {
        Matcher matcher = PO_PATTERN.matcher(request == null ? "" : request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("请求里没有采购单号（格式 PO-数字），例如：帮我处理 PO-10001 的付款");
        }
        return createRun(request, matcher.group(), null);
    }

    /**
     * 创建任务和它的第一次运行（QUEUED）。任务、运行、run.created 事件、占住付款单在同一个事务里：
     * 同一张付款单并发发起，只有一个能建出运行。
     */
    public TaskCreated createRun(String request, String po, Long paymentOrderId) {
        RunTargetResolver resolver = targets.getIfAvailable();
        LocalDateTime now = LocalDateTime.now();
        return tx.execute(s -> {
            RunTarget target = resolver == null ? new RunTarget(po, null, null) : resolver.resolve(po, paymentOrderId);

            TaskEntity task = new TaskEntity();
            task.setId(Ids.next("task"));
            task.setRequest(request);
            task.setTaskType(TASK_TYPE);
            task.setPoNumber(target.poNumber());
            task.setCreatedAt(now);

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setId(Ids.next("run"));
            run.setTaskId(task.getId());
            run.setPoNumber(target.poNumber());
            run.setInvoiceNumber(target.invoiceNumber());
            run.setPaymentOrderId(target.paymentOrderId());
            run.setStatus(RunStatus.QUEUED.name());
            run.setData(json.write(fields("po_number", target.poNumber(), "invoice_id", target.invoiceNumber())));
            run.setEventSequence(0);
            run.setRetryCount(0);
            run.setCreatedAt(now);
            run.setUpdatedAt(now);

            taskMapper.insert(task);
            runMapper.insert(run);
            events.append(run.getId(), "run.created", fields("task_id", task.getId(), "po_number", target.poNumber(),
                    "invoice_number", target.invoiceNumber(), "payment_order_id", target.paymentOrderId()));
            if (resolver != null) {
                resolver.onRunCreated(target, run.getId());
            }
            return new TaskCreated(task.getId(), run.getId(), target.poNumber(), run.getStatus());
        });
    }

    /**
     * 启动：QUEUED → RUNNING（条件更新，重复启动只有一次成功，其他返回 409），同一个事务里写发件箱，
     * 提交后触发投递。就算投递之前进程崩溃，消息还在发件箱里，RUNNING 状态也会被恢复扫描接手。
     */
    public RunView start(String runId) {
        WorkflowRunEntity run = require(runId);
        boolean started = Boolean.TRUE.equals(tx.execute(s -> {
            if (!runStore.transition(runId, RunStatus.QUEUED,
                    RunUpdate.to(RunStatus.RUNNING).step("fetch_order").started(LocalDateTime.now()))) {
                return false;
            }
            events.append(runId, "run.started", fields("po_number", run.getPoNumber()));
            outbox.enqueueDispatch(runId, "run.started");
            return true;
        }));
        if (!started) {
            String current = require(runId).getStatus();
            throw new ConflictException("运行当前状态为 " + current + "，不能启动", current);
        }
        relay.kick();
        return get(runId);
    }

    /** 不等重试时间，马上再试一次（清掉 retry_at，写发件箱，提交后投递）。 */
    public RunView resume(String runId) {
        WorkflowRunEntity run = require(runId);
        RunStatus status = RunStatus.valueOf(run.getStatus());
        if (!status.isRecoverable()) {
            throw new ConflictException("只有 RUNNING / READY_TO_SUBMIT 的运行可以继续，当前为 " + status, status.name());
        }
        tx.executeWithoutResult(s -> {
            runStore.transition(runId, status, RunUpdate.to(status).clearRetryAt());
            outbox.enqueueDispatch(runId, "run.resumed");
        });
        relay.kick();
        return get(runId);
    }

    public RunView get(String runId) {
        return RunView.of(require(runId), json);
    }

    public List<RunView> list(String status, int size) {
        String normalized = status == null || status.isBlank() ? null : RunStatus.valueOf(status.toUpperCase()).name();
        return runMapper.selectRecent(normalized, Math.min(Math.max(size, 1), 100)).stream()
                .map(r -> RunView.of(r, json))
                .toList();
    }

    public List<EventView> events(String runId, int after) {
        require(runId);
        return events.list(runId, after).stream().map(e -> EventView.of(e, json)).toList();
    }

    private WorkflowRunEntity require(String runId) {
        WorkflowRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new NotFoundException("运行不存在: " + runId);
        }
        return run;
    }
}
