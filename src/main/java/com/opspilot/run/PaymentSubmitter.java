package com.opspilot.run;

import com.opspilot.config.JsonCodec;
import com.opspilot.event.EventService;
import com.opspilot.gateway.BusinessSystemGateway;
import com.opspilot.gateway.Payment;
import com.opspilot.gateway.PaymentRequest;
import com.opspilot.gateway.SubmitException;
import com.opspilot.gateway.SubmitResult;
import com.opspilot.gateway.UpstreamUnavailableException;
import com.opspilot.idempotency.ClaimResult;
import com.opspilot.idempotency.IdempotencyService;
import com.opspilot.store.IdempotencyRecordEntity;
import com.opspilot.store.WorkflowRunEntity;
import com.opspilot.store.WorkflowRunMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static com.opspilot.config.JsonCodec.fields;

/**
 * 提交付款：整个系统里唯一有外部副作用的步骤，目标是"不重复付款、也不漏付款"。
 *
 * <pre>
 * 1. 幂等记录是 PENDING（上次提交结果不明）→ 先去财务系统按业务键对账，查到了就直接完成，不再提交
 * 2. 抢占幂等记录：ACQUIRED / RECLAIMED 才能提交；COMPLETED 直接用结果；OCCUPIED 推迟
 * 3. 提交，然后按业务键回查核实（不只相信 HTTP 响应）
 * 4. "幂等记录完成 + 运行完成 + 事件"在同一个事务里提交
 * </pre>
 * 提交失败按对方是否可能收到分别处理，见 {@link SubmitException}。
 */
@Component
public class PaymentSubmitter {

    private static final Logger log = LoggerFactory.getLogger(PaymentSubmitter.class);
    static final String OPERATION = "submit_payment";
    static final String COST_CENTER = "IT-OPS";
    static final String REASON = "Delivered equipment";

    private final WorkflowRunMapper runMapper;
    private final RunStore runStore;
    private final EventService events;
    private final IdempotencyService idempotency;
    private final BusinessSystemGateway gateway;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final Counter submitRequests;

    public PaymentSubmitter(WorkflowRunMapper runMapper, RunStore runStore, EventService events,
                            IdempotencyService idempotency, BusinessSystemGateway gateway,
                            TransactionTemplate tx, JsonCodec json, MeterRegistry meterRegistry) {
        this.runMapper = runMapper;
        this.runStore = runStore;
        this.events = events;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.tx = tx;
        this.json = json;
        // 实际发给财务系统的付款请求次数：用来证明"同一笔付款只发一次"
        this.submitRequests = Counter.builder("opspilot.payment.submit.requests")
                .description("发往财务系统的付款提交请求次数")
                .register(meterRegistry);
    }

    public static String idempotencyKey(String poNumber, String invoiceId) {
        return "payment:" + poNumber + ":" + invoiceId;
    }

    /** 由 {@link RunExecutor} 在持有执行租约（owner）时调用。 */
    public void submit(String runId, String owner) {
        WorkflowRunEntity run = runMapper.selectById(runId);
        if (run == null || !RunStatus.READY_TO_SUBMIT.name().equals(run.getStatus())) {
            return;
        }
        Map<String, Object> data = json.readMap(run.getData());
        String po = (String) data.get("po_number");
        String invoiceId = (String) data.get("invoice_id");
        String key = idempotencyKey(po, invoiceId);
        int retries = run.getRetryCount() == null ? 0 : run.getRetryCount();

        try {
            // 1. 上一次提交结果不明：先对账
            IdempotencyRecordEntity existing = idempotency.find(key);
            if (existing != null && "PENDING".equals(existing.getStatus())) {
                Optional<Payment> found = gateway.findPayment(po, invoiceId);
                if (found.isPresent()) {
                    complete(runId, owner, data, key, found.get(), "payment.reconciled",
                            fields("idempotency_key", key, "payment_id", found.get().id(),
                                    "note", "上次提交结果不明，对账发现付款已存在，不再重复提交"));
                    return;
                }
            }

            // 2. 抢占
            ClaimResult claim = idempotency.claim(key, OPERATION, runId);
            switch (claim.outcome()) {
                case COMPLETED -> {
                    completeFromRecord(runId, owner, data, key, claim.record());
                    return;
                }
                case OCCUPIED -> {
                    defer(runId, owner, key, claim.record());
                    return;
                }
                default -> {
                    // ACQUIRED / RECLAIMED：继续提交
                }
            }

            // 3. 提交
            events.append(runId, "submission.started", fields("idempotency_key", key,
                    "claim", claim.outcome().name(), "attempt", claim.record() == null ? null : claim.record().getAttempt()));
            submitRequests.increment();
            SubmitResult result;
            try {
                result = gateway.submitPayment(new PaymentRequest(po, invoiceId, (String) data.get("supplier"),
                        new BigDecimal(String.valueOf(data.get("amount"))), COST_CENTER, REASON));
            } catch (SubmitException e) {
                handleSubmitFailure(runId, owner, retries, key, e);
                return;
            }

            // 4. 核实：按业务键回查，确认财务系统里真的有这笔
            Optional<Payment> verified = gateway.findPayment(po, invoiceId);
            if (verified.isEmpty()) {
                retryLater(runId, owner, retries, "submission.ambiguous", fields("idempotency_key", key,
                        "reason", "提交返回成功，但回查不到付款；保留幂等记录，下次先对账"));
                return;
            }
            complete(runId, owner, data, key, verified.get(),
                    result.created() ? "payment.submitted" : "payment.duplicate_suppressed",
                    fields("idempotency_key", key, "payment_id", verified.get().id(),
                            "created", result.created(),
                            "note", result.created() ? null : "财务系统里已有这笔付款，返回的是已有记录"));
        } catch (UpstreamUnavailableException e) {
            // 对账或核实时查询失败：退避后再来
            log.warn("运行 {} 查询财务系统失败: {}", runId, e.getMessage());
            retryLater(runId, owner, retries, "submission.deferred",
                    fields("idempotency_key", key, "reason", e.getMessage()));
        }
    }

    private void handleSubmitFailure(String runId, String owner, int retries, String key, SubmitException e) {
        switch (e.kind()) {
            case NOT_SENT -> {
                // 请求没发出去：释放记录，下次可以直接重新提交，不用等幂等租约过期
                idempotency.release(key, runId);
                retryLater(runId, owner, retries, "submission.failed", fields("idempotency_key", key,
                        "retryable", true, "reason", e.getMessage()));
            }
            case OUTCOME_UNKNOWN -> {
                // 对方可能已经付了：保留 PENDING 记录，运行留在 READY_TO_SUBMIT，下次先对账。
                // 结果不明的付款不会自动放弃（只会把退避拉长到 5 分钟），需要配合告警人工介入
                retryLater(runId, owner, retries, "submission.ambiguous", fields("idempotency_key", key,
                        "reason", e.getMessage()));
            }
            case REJECTED -> {
                idempotency.release(key, runId);
                tx.executeWithoutResult(s -> {
                    if (runStore.transition(runId, RunStatus.READY_TO_SUBMIT, RunUpdate.to(RunStatus.FAILED)
                            .error("PAYMENT_REJECTED", e.getMessage()).finished(LocalDateTime.now())
                            .clearRetryAt().ownedBy(owner))) {
                        events.append(runId, "run.failed", fields("error_type", "PAYMENT_REJECTED",
                                "message", e.getMessage()));
                    }
                });
            }
        }
    }

    /** 指数退避：运行留在 READY_TO_SUBMIT，retry_at 到了恢复扫描会自动再来。 */
    private void retryLater(String runId, String owner, int retries, String eventType, Map<String, Object> payload) {
        int delay = RunStore.backoffSeconds(retries);
        payload.put("retry_in_seconds", delay);
        tx.executeWithoutResult(s -> {
            if (runStore.transition(runId, RunStatus.READY_TO_SUBMIT,
                    RunUpdate.to(RunStatus.READY_TO_SUBMIT).retryAfter(delay).ownedBy(owner))) {
                events.append(runId, eventType, payload);
            }
        });
    }

    /** 别人正在提交同一笔付款：等他的幂等租约到期再看（恢复扫描按 retry_at 自动重试）。 */
    private void defer(String runId, String owner, String key, IdempotencyRecordEntity record) {
        tx.executeWithoutResult(s -> {
            if (runStore.transition(runId, RunStatus.READY_TO_SUBMIT,
                    RunUpdate.to(RunStatus.READY_TO_SUBMIT).retryAt(record.getLeaseExpiresAt()).ownedBy(owner))) {
                events.append(runId, "submission.deferred", fields("idempotency_key", key,
                        "owner_run_id", record.getOwnerRunId(),
                        "retry_at", String.valueOf(record.getLeaseExpiresAt())));
            }
        });
    }

    /** 幂等记录已经完成：直接用保存的结果完成本运行，不发请求。 */
    private void completeFromRecord(String runId, String owner, Map<String, Object> data, String key,
                                    IdempotencyRecordEntity record) {
        Map<String, Object> payment = json.readMap(record.getResult());
        tx.executeWithoutResult(s -> finish(runId, owner, data, payment, "payment.duplicate_suppressed",
                fields("idempotency_key", key, "payment_id", payment.get("id"),
                        "note", "这笔付款已经由 " + record.getOwnerRunId() + " 完成，本次没有发请求")));
    }

    private void complete(String runId, String owner, Map<String, Object> data, String key, Payment payment,
                          String eventType, Map<String, Object> eventPayload) {
        Map<String, Object> paymentMap = json.toMap(payment);
        tx.executeWithoutResult(s -> {
            // 幂等记录完成和运行完成在同一个事务：不会出现"运行完成了，记录还是 PENDING"
            idempotency.complete(key, json.write(paymentMap));
            finish(runId, owner, data, paymentMap, eventType, eventPayload);
        });
    }

    private void finish(String runId, String owner, Map<String, Object> data, Map<String, Object> payment,
                        String eventType, Map<String, Object> eventPayload) {
        data.put("payment", payment);
        if (!runStore.transition(runId, RunStatus.READY_TO_SUBMIT, RunUpdate.to(RunStatus.COMPLETED)
                .step("done").data(json.write(data)).clearRetryAt().resetRetryCount()
                .finished(LocalDateTime.now()).ownedBy(owner))) {
            return;
        }
        events.append(runId, eventType, eventPayload);
        events.append(runId, "side_effect.verified", fields("payment_id", payment.get("id"),
                "status", payment.get("status")));
        events.append(runId, "run.completed", fields("payment_id", payment.get("id")));
    }
}
