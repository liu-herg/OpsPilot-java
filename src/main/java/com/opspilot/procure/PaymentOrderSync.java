package com.opspilot.procure;

import com.opspilot.config.JsonCodec;
import com.opspilot.procure.ProcureStatus.Payment;
import com.opspilot.run.RunFinished;
import com.opspilot.store.PaymentOrderEntity;
import com.opspilot.store.PaymentOrderMapper;
import com.opspilot.store.InvoiceMapper;
import com.opspilot.store.PurchaseOrderLineMapper;
import com.opspilot.store.PurchaseOrderMapper;
import com.opspilot.store.WorkflowRunEntity;
import com.opspilot.store.WorkflowRunMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运行结束 → 同步付款单、发票、订单的状态。监听是同步的，在运行状态转移的<b>同一个事务</b>里执行：
 * 不会出现"运行已经完成，付款单还是执行中"。
 * <ul>
 *   <li>COMPLETED：付款单 PAID（记下财务系统的付款单号），发票 PAID；订单开票开满且没有未付的付款单 → CLOSED</li>
 *   <li>FAILED：付款单 FAILED，可以重新发起</li>
 *   <li>REJECTED（大额审批被驳回）：付款单 REJECTED</li>
 * </ul>
 */
@Component
public class PaymentOrderSync {

    private final PaymentOrderMapper paymentMapper;
    private final InvoiceMapper invoiceMapper;
    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderLineMapper lineMapper;
    private final WorkflowRunMapper runMapper;
    private final JsonCodec json;

    public PaymentOrderSync(PaymentOrderMapper paymentMapper, InvoiceMapper invoiceMapper,
                            PurchaseOrderMapper orderMapper, PurchaseOrderLineMapper lineMapper,
                            WorkflowRunMapper runMapper, JsonCodec json) {
        this.paymentMapper = paymentMapper;
        this.invoiceMapper = invoiceMapper;
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.runMapper = runMapper;
        this.json = json;
    }

    @EventListener
    public void onRunFinished(RunFinished event) {
        PaymentOrderEntity p = paymentMapper.selectByRun(event.runId());
        if (p == null) {
            return;
        }
        switch (event.status()) {
            case COMPLETED -> {
                paymentMapper.finishByRun(event.runId(), Payment.PAID.name(), financePaymentId(event.runId()));
                invoiceMapper.transition(p.getInvoiceId(), ProcureStatus.Invoice.PAYABLE.name(),
                        ProcureStatus.Invoice.PAID.name());
                if (paymentMapper.countUnpaid(p.getPoId()) == 0 && lineMapper.fullyInvoiced(p.getPoId())) {
                    orderMapper.transition(p.getPoId(), ProcureStatus.Order.RECEIVED.name(),
                            ProcureStatus.Order.CLOSED.name());
                }
            }
            case FAILED -> paymentMapper.finishByRun(event.runId(), Payment.FAILED.name(), null);
            case REJECTED -> paymentMapper.finishByRun(event.runId(), Payment.REJECTED.name(), null);
            default -> {
                // 不是终态，不会发布
            }
        }
    }

    /** 运行完成时 data.payment 里是财务系统返回的付款记录。 */
    private String financePaymentId(String runId) {
        WorkflowRunEntity run = runMapper.selectById(runId);
        Object payment = run == null ? null : json.readMap(run.getData()).get("payment");
        if (payment instanceof Map<?, ?> m && m.get("id") != null) {
            return String.valueOf(m.get("id"));
        }
        return null;
    }
}
