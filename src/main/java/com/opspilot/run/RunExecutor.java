package com.opspilot.run;

import com.opspilot.config.JsonCodec;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.event.EventService;
import com.opspilot.gateway.BusinessSystemGateway;
import com.opspilot.gateway.Invoice;
import com.opspilot.gateway.PurchaseOrder;
import com.opspilot.gateway.UpstreamUnavailableException;
import com.opspilot.store.ApprovalEntity;
import com.opspilot.store.ApprovalMapper;
import com.opspilot.store.WorkflowRunEntity;
import com.opspilot.store.WorkflowRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.opspilot.config.JsonCodec.fields;

/**
 * 发票付款流程的执行逻辑：查采购单 → 查发票 → 校验一致 → 策略（大额走审批）→ 提交付款。
 * 只由 {@link RunWorker} 在持有执行租约时调用；所有状态转移都带上租约持有者（fencing）。
 *
 * <p>崩溃恢复靠"可以安全重放"：RUNNING 状态的运行被接手后从头执行，
 * 前面的步骤都是只读查询 + 确定性的校验，重放没有副作用；审批单 id 由运行 id 决定，重放不会建出第二张；
 * 唯一有副作用的付款由幂等记录 + 对账保证只做一次。
 *
 * <p>事务边界：远程调用（HTTP）一律在事务外面；只有"状态转移 + 事件"这样的短操作放进事务。
 */
@Component
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    private final WorkflowRunMapper runMapper;
    private final ApprovalMapper approvalMapper;
    private final RunStore runStore;
    private final EventService events;
    private final BusinessSystemGateway gateway;
    private final PaymentSubmitter paymentSubmitter;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final long approvalThreshold;
    private final int maxReadRetries;

    public RunExecutor(WorkflowRunMapper runMapper, ApprovalMapper approvalMapper, RunStore runStore,
                       EventService events, BusinessSystemGateway gateway, PaymentSubmitter paymentSubmitter,
                       TransactionTemplate tx, JsonCodec json, OpsPilotProperties properties) {
        this.runMapper = runMapper;
        this.approvalMapper = approvalMapper;
        this.runStore = runStore;
        this.events = events;
        this.gateway = gateway;
        this.paymentSubmitter = paymentSubmitter;
        this.tx = tx;
        this.json = json;
        this.approvalThreshold = properties.approvalThreshold();
        this.maxReadRetries = properties.worker().maxReadRetries();
    }

    /** 推进一个运行：根据当前状态决定做什么。owner 是当前持有的执行租约。 */
    public void execute(String runId, String owner) {
        WorkflowRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        switch (RunStatus.valueOf(run.getStatus())) {
            case RUNNING -> executeSteps(run, owner);
            case READY_TO_SUBMIT -> paymentSubmitter.submit(runId, owner);
            default -> {
                // 已经被别人推进到其他状态，什么都不用做
            }
        }
    }

    private void executeSteps(WorkflowRunEntity run, String owner) {
        String runId = run.getId();
        try {
            steps(runId, run.getPoNumber(), owner);
        } catch (UpstreamUnavailableException e) {
            retryLaterOrFail(run, owner, e.getMessage());
        } catch (StepAborted e) {
            log.info("运行 {} 停止执行：{}", runId, e.getMessage());
        } catch (RuntimeException e) {
            // 兜底：不让运行卡在 RUNNING。如果已经进入 READY_TO_SUBMIT，这里的条件更新不会生效
            log.error("运行 {} 执行出错", runId, e);
            fail(runId, RunStatus.RUNNING, owner, "INTERNAL_ERROR", e.getMessage(), Map.of());
        }
    }

    /** 只读步骤遇到外部系统故障：指数退避后再试，超过次数才失败。 */
    private void retryLaterOrFail(WorkflowRunEntity run, String owner, String reason) {
        int retries = run.getRetryCount() == null ? 0 : run.getRetryCount();
        if (retries >= maxReadRetries) {
            fail(run.getId(), RunStatus.RUNNING, owner, "UPSTREAM_UNAVAILABLE",
                    "重试 " + retries + " 次后业务系统仍不可用: " + reason, Map.of());
            return;
        }
        int delay = RunStore.backoffSeconds(retries);
        tx.executeWithoutResult(s -> {
            if (runStore.transition(run.getId(), RunStatus.RUNNING,
                    RunUpdate.to(RunStatus.RUNNING).retryAfter(delay).ownedBy(owner))) {
                events.append(run.getId(), "step.retry_scheduled", fields(
                        "reason", reason, "retry_count", retries + 1, "retry_in_seconds", delay));
            }
        });
    }

    private void steps(String runId, String po, String owner) {
        PurchaseOrder order = step(runId, owner, "fetch_order", () -> gateway.findOrder(po));
        if (order == null) {
            fail(runId, RunStatus.RUNNING, owner, "NOT_FOUND", "采购系统里没有 " + po, Map.of());
            return;
        }
        Invoice invoice = step(runId, owner, "fetch_invoice", () -> gateway.findInvoiceByPo(po));
        if (invoice == null) {
            fail(runId, RunStatus.RUNNING, owner, "NOT_FOUND", "发票系统里没有 " + po + " 对应的发票", Map.of());
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("po_number", po);
        data.put("invoice_id", invoice.invoiceId());
        data.put("supplier", order.supplier());
        data.put("amount", order.amount());
        data.put("currency", order.currency());

        // 校验：采购单和发票的供应商、金额必须一致
        enterStep(runId, owner, "validate");
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
            fail(runId, RunStatus.RUNNING, owner, "BUSINESS_VALIDATION_FAILED", "采购单与发票不一致", data);
            return;
        }
        events.append(runId, "step.completed", fields("step", "validate"));

        // 策略：大额付款必须人工审批
        if (order.amount() > approvalThreshold) {
            requestApproval(runId, owner, data);
            return;
        }
        boolean ready = Boolean.TRUE.equals(tx.execute(s -> {
            if (!runStore.transition(runId, RunStatus.RUNNING, RunUpdate.to(RunStatus.READY_TO_SUBMIT)
                    .step("submit_payment").data(json.write(data)).resetRetryCount().clearRetryAt().ownedBy(owner))) {
                return false;
            }
            events.append(runId, "policy.auto_approved",
                    fields("amount", order.amount(), "threshold", approvalThreshold));
            return true;
        }));
        if (!ready) {
            throw new StepAborted("进入 READY_TO_SUBMIT 失败（状态或租约已变）");
        }
        paymentSubmitter.submit(runId, owner);
    }

    /** 审批单的 id 由运行 id 决定：策略步骤被重放（崩溃恢复）时，也只会有一张审批单。 */
    private void requestApproval(String runId, String owner, Map<String, Object> data) {
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
                // 重放时已经建过，沿用
            }
            if (!runStore.transition(runId, RunStatus.RUNNING, RunUpdate.to(RunStatus.WAITING_APPROVAL)
                    .step("approval").data(json.write(data)).resetRetryCount().clearRetryAt().ownedBy(owner))) {
                // 抛异常让审批单的插入一起回滚
                throw new StepAborted("进入 WAITING_APPROVAL 失败（状态或租约已变）");
            }
            events.append(runId, "approval.requested", fields(
                    "approval_id", approvalId, "amount", data.get("amount"), "threshold", approvalThreshold));
        });
    }

    /** 执行一个只读步骤：先记"开始"（同时续租），查询在事务外，成功后记"完成"。 */
    private <T> T step(String runId, String owner, String step, Supplier<Optional<T>> query) {
        enterStep(runId, owner, step);
        T result = query.get().orElse(null);
        events.append(runId, "step.completed", fields("step", step, "found", result != null,
                "output", result == null ? null : json.toMap(result)));
        return result;
    }

    private void enterStep(String runId, String owner, String step) {
        tx.executeWithoutResult(s -> {
            if (!runStore.transition(runId, RunStatus.RUNNING,
                    RunUpdate.to(RunStatus.RUNNING).step(step).ownedBy(owner))) {
                throw new StepAborted("进入步骤 " + step + " 失败（状态或租约已变）");
            }
            events.append(runId, "step.started", fields("step", step));
        });
    }

    private void fail(String runId, RunStatus from, String owner, String errorType, String message,
                      Map<String, Object> data) {
        tx.executeWithoutResult(s -> {
            RunUpdate update = RunUpdate.to(RunStatus.FAILED).error(errorType, message)
                    .finished(LocalDateTime.now()).clearRetryAt().ownedBy(owner);
            if (!data.isEmpty()) {
                update.data(json.write(data));
            }
            if (runStore.transition(runId, from, update)) {
                events.append(runId, "run.failed", fields("error_type", errorType, "message", message,
                        "mismatches", data.get("mismatches")));
            }
        });
    }

    /** 状态或租约被并发修改，当前执行者应该停下（不是错误，别人已经接手了）。 */
    static class StepAborted extends RuntimeException {
        StepAborted(String message) {
            super(message);
        }
    }
}
