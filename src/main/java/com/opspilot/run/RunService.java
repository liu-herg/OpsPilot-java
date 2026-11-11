package com.opspilot.run;

import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.config.Ids;
import com.opspilot.config.JsonCodec;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.event.EventService;
import com.opspilot.gateway.BusinessSystemGateway;
import com.opspilot.gateway.Invoice;
import com.opspilot.gateway.PurchaseOrder;
import com.opspilot.gateway.UpstreamUnavailableException;
import com.opspilot.store.ApprovalEntity;
import com.opspilot.store.ApprovalMapper;
import com.opspilot.store.TaskEntity;
import com.opspilot.store.TaskMapper;
import com.opspilot.store.WorkflowRunEntity;
import com.opspilot.store.WorkflowRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.opspilot.config.JsonCodec.fields;

/**
 * 发票付款流程：查采购单 → 查发票 → 校验一致 → 策略（大额走审批）→ 提交付款。
 *
 * <p>事务边界：远程调用（HTTP）一律放在事务外面；只有"状态转移 + 事件"这样的短操作放进事务。
 * 否则一次 5 秒的网络超时就会占着数据库连接和行锁 5 秒。
 * 用 {@link TransactionTemplate} 编程式事务，边界写在代码里一眼能看到，也没有 @Transactional 自调用失效的问题。
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final Pattern PO_PATTERN = Pattern.compile("PO-\\d+");
    static final String TASK_TYPE = "invoice_payment";

    private final TaskMapper taskMapper;
    private final WorkflowRunMapper runMapper;
    private final ApprovalMapper approvalMapper;
    private final RunStore runStore;
    private final EventService events;
    private final BusinessSystemGateway gateway;
    private final PaymentSubmitter paymentSubmitter;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final long approvalThreshold;

    public RunService(TaskMapper taskMapper, WorkflowRunMapper runMapper, ApprovalMapper approvalMapper,
                      RunStore runStore, EventService events, BusinessSystemGateway gateway,
                      PaymentSubmitter paymentSubmitter, TransactionTemplate tx, JsonCodec json,
                      OpsPilotProperties properties) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.approvalMapper = approvalMapper;
        this.runStore = runStore;
        this.events = events;
        this.gateway = gateway;
        this.paymentSubmitter = paymentSubmitter;
        this.tx = tx;
        this.json = json;
        this.approvalThreshold = properties.approvalThreshold();
    }

    // ---------------------------------------------------------------- 创建与查询

    /** 创建任务和它的第一次运行（QUEUED），任务、运行、run.created 事件在同一个事务里。 */
    public TaskCreated createTask(String request) {
        Matcher matcher = PO_PATTERN.matcher(request == null ? "" : request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("请求里没有采购单号（格式 PO-数字），例如：帮我处理 PO-10001 的付款");
        }
        String po = matcher.group();
        LocalDateTime now = LocalDateTime.now();

        TaskEntity task = new TaskEntity();
        task.setId(Ids.next("task"));
        task.setRequest(request);
        task.setTaskType(TASK_TYPE);
        task.setPoNumber(po);
        task.setCreatedAt(now);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(Ids.next("run"));
        run.setTaskId(task.getId());
        run.setPoNumber(po);
        run.setStatus(RunStatus.QUEUED.name());
        run.setData(json.write(fields("po_number", po)));
        run.setEventSequence(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        tx.executeWithoutResult(s -> {
            taskMapper.insert(task);
            runMapper.insert(run);
            events.append(run.getId(), "run.created", fields("task_id", task.getId(), "po_number", po));
        });
        return new TaskCreated(task.getId(), run.getId(), po, run.getStatus());
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

    // ---------------------------------------------------------------- 执行

    /**
     * 启动运行（P1 同步执行，P2 改为异步 + 启动恢复）。
     * QUEUED → RUNNING 是条件更新：同一个运行被重复点"启动"，只有一次能成功，其他返回 409。
     */
    public RunView start(String runId) {
        WorkflowRunEntity run = require(runId);
        boolean started = Boolean.TRUE.equals(tx.execute(s -> {
            if (!runStore.transition(runId, RunStatus.QUEUED,
                    RunUpdate.to(RunStatus.RUNNING).step("fetch_order").started(LocalDateTime.now()))) {
                return false;
            }
            events.append(runId, "run.started", fields("po_number", run.getPoNumber()));
            return true;
        }));
        if (!started) {
            String current = require(runId).getStatus();
            throw new ConflictException("运行当前状态为 " + current + "，不能启动", current);
        }

        try {
            execute(runId, run.getPoNumber());
        } catch (UpstreamUnavailableException e) {
            log.warn("运行 {} 访问业务系统失败: {}", runId, e.getMessage());
            fail(runId, RunStatus.RUNNING, "UPSTREAM_UNAVAILABLE", e.getMessage(), Map.of());
        } catch (StepAborted e) {
            log.info("运行 {} 在执行中被并发修改，停止执行: {}", runId, e.getMessage());
        } catch (RuntimeException e) {
            // 兜底：不让运行卡在 RUNNING。如果已经进入 READY_TO_SUBMIT，这里的条件更新不会生效
            log.error("运行 {} 执行出错", runId, e);
            fail(runId, RunStatus.RUNNING, "INTERNAL_ERROR", e.getMessage(), Map.of());
        }
        return get(runId);
    }

    /** 继续执行停在 READY_TO_SUBMIT 的运行（提交结果不确定 / 被占用 / 连接失败之后）。 */
    public RunView resume(String runId) {
        WorkflowRunEntity run = require(runId);
        if (!RunStatus.READY_TO_SUBMIT.name().equals(run.getStatus())) {
            throw new ConflictException("只有 READY_TO_SUBMIT 的运行可以继续，当前为 " + run.getStatus(), run.getStatus());
        }
        paymentSubmitter.submit(runId);
        return get(runId);
    }

    private void execute(String runId, String po) {
        PurchaseOrder order = step(runId, "fetch_order", () -> gateway.findOrder(po));
        if (order == null) {
            fail(runId, RunStatus.RUNNING, "NOT_FOUND", "采购系统里没有 " + po, Map.of());
            return;
        }
        Invoice invoice = step(runId, "fetch_invoice", () -> gateway.findInvoiceByPo(po));
        if (invoice == null) {
            fail(runId, RunStatus.RUNNING, "NOT_FOUND", "发票系统里没有 " + po + " 对应的发票", Map.of());
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("po_number", po);
        data.put("invoice_id", invoice.invoiceId());
        data.put("supplier", order.supplier());
        data.put("amount", order.amount());
        data.put("currency", order.currency());

        // 校验：采购单和发票的供应商、金额必须一致
        enterStep(runId, "validate");
        List<Map<String, Object>> mismatches = new ArrayList<>();
        if (!Objects.equals(order.supplier(), invoice.supplier())) {
            mismatches.add(fields("field", "supplier", "order", order.supplier(), "invoice", invoice.supplier()));
        }
        if (order.amount() != invoice.amount()) {
            mismatches.add(fields("field", "amount", "order", order.amount(), "invoice", invoice.amount()));
        }
        if (!po.equals(invoice.poId())) {
            mismatches.add(fields("field", "po_id", "order", po, "invoice", invoice.poId()));
        }
        if (!mismatches.isEmpty()) {
            data.put("mismatches", mismatches);
            fail(runId, RunStatus.RUNNING, "BUSINESS_VALIDATION_FAILED", "采购单与发票不一致", data);
            return;
        }
        events.append(runId, "step.completed", fields("step", "validate"));

        // 策略：大额付款必须人工审批
        if (order.amount() > approvalThreshold) {
            requestApproval(runId, data);
            return;
        }
        boolean ready = Boolean.TRUE.equals(tx.execute(s -> {
            if (!runStore.transition(runId, RunStatus.RUNNING,
                    RunUpdate.to(RunStatus.READY_TO_SUBMIT).step("submit_payment").data(json.write(data)))) {
                return false;
            }
            events.append(runId, "policy.auto_approved",
                    fields("amount", order.amount(), "threshold", approvalThreshold));
            return true;
        }));
        if (!ready) {
            throw new StepAborted("进入 READY_TO_SUBMIT 失败");
        }
        paymentSubmitter.submit(runId);
    }

    /** 审批单的 id 由运行 id 决定：策略步骤即使被重复执行（比如故障恢复），也只会有一张审批单。 */
    private void requestApproval(String runId, Map<String, Object> data) {
        String approvalId = "apr-" + runId.substring(runId.indexOf('-') + 1);
        data.put("approval_id", approvalId);
        tx.executeWithoutResult(s -> {
            ApprovalEntity approval = new ApprovalEntity();
            approval.setId(approvalId);
            approval.setRunId(runId);
            approval.setRisk("HIGH");
            approval.setPayload(json.write(fields(
                    "po_number", data.get("po_number"), "invoice_id", data.get("invoice_id"),
                    "supplier", data.get("supplier"), "amount", data.get("amount"),
                    "currency", data.get("currency"), "threshold", approvalThreshold)));
            approval.setStatus("PENDING");
            approval.setCreatedAt(LocalDateTime.now());
            try {
                approvalMapper.insert(approval);
            } catch (DuplicateKeyException alreadyRequested) {
                // 已经建过，沿用
            }
            if (!runStore.transition(runId, RunStatus.RUNNING,
                    RunUpdate.to(RunStatus.WAITING_APPROVAL).step("approval").data(json.write(data)))) {
                // 抛异常让审批单的插入一起回滚
                throw new StepAborted("进入 WAITING_APPROVAL 失败");
            }
            events.append(runId, "approval.requested", fields(
                    "approval_id", approvalId, "amount", data.get("amount"), "threshold", approvalThreshold));
        });
    }

    /** 执行一个只读步骤：先记"开始"，查询在事务外，成功后记"完成"。 */
    private <T> T step(String runId, String step, Supplier<Optional<T>> query) {
        enterStep(runId, step);
        T result = query.get().orElse(null);
        events.append(runId, "step.completed", fields("step", step, "found", result != null,
                "output", result == null ? null : json.toMap(result)));
        return result;
    }

    private void enterStep(String runId, String step) {
        tx.executeWithoutResult(s -> {
            if (!runStore.transition(runId, RunStatus.RUNNING, RunUpdate.to(RunStatus.RUNNING).step(step))) {
                throw new StepAborted("进入步骤 " + step + " 失败");
            }
            events.append(runId, "step.started", fields("step", step));
        });
    }

    private void fail(String runId, RunStatus from, String errorType, String message, Map<String, Object> data) {
        tx.executeWithoutResult(s -> {
            RunUpdate update = RunUpdate.to(RunStatus.FAILED).error(errorType, message).finished(LocalDateTime.now());
            if (!data.isEmpty()) {
                update.data(json.write(data));
            }
            if (runStore.transition(runId, from, update)) {
                events.append(runId, "run.failed", fields("error_type", errorType, "message", message,
                        "mismatches", data.get("mismatches")));
            }
        });
    }

    private WorkflowRunEntity require(String runId) {
        WorkflowRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new NotFoundException("运行不存在: " + runId);
        }
        return run;
    }

    /** 状态被并发修改，当前执行线程应该停下（不是错误，别人已经接手了）。 */
    static class StepAborted extends RuntimeException {
        StepAborted(String message) {
            super(message);
        }
    }
}
