package com.opspilot.procure;

import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.procure.ProcureStatus.Payment;
import com.opspilot.run.RunTarget;
import com.opspilot.run.RunTargetResolver;
import com.opspilot.store.InvoiceEntity;
import com.opspilot.store.InvoiceMapper;
import com.opspilot.store.PaymentOrderEntity;
import com.opspilot.store.PaymentOrderMapper;
import com.opspilot.store.PurchaseOrderEntity;
import com.opspilot.store.PurchaseOrderMapper;
import org.springframework.stereotype.Component;

/**
 * 创建运行时找到要付的那张付款单（P4 起运行都对应付款单）。在创建运行的事务里执行：
 * 先锁住付款单、确认它还是 PENDING，运行插入后再把它改成 EXECUTING，所以同一张付款单不会被发起两次。
 */
@Component
public class PaymentOrderRunTargets implements RunTargetResolver {

    private final PaymentOrderMapper paymentMapper;
    private final PurchaseOrderMapper orderMapper;
    private final InvoiceMapper invoiceMapper;

    public PaymentOrderRunTargets(PaymentOrderMapper paymentMapper, PurchaseOrderMapper orderMapper,
                                  InvoiceMapper invoiceMapper) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.invoiceMapper = invoiceMapper;
    }

    @Override
    public RunTarget resolve(String poNumber, Long paymentOrderId) {
        PaymentOrderEntity p;
        if (paymentOrderId != null) {
            p = paymentMapper.lockById(paymentOrderId);
            if (p == null) {
                throw new NotFoundException("付款单不存在: " + paymentOrderId);
            }
        } else {
            p = paymentMapper.lockFirstPendingByPoNumber(poNumber);
            if (p == null) {
                throw new ConflictException("采购订单 " + poNumber + " 没有待付款的付款单", null);
            }
        }
        if (!Payment.PENDING.name().equals(p.getStatus())) {
            throw new ConflictException("付款单 " + p.getPayNumber() + " 当前状态为 " + p.getStatus() + "，不能发起付款",
                    p.getStatus());
        }
        PurchaseOrderEntity po = orderMapper.selectById(p.getPoId());
        InvoiceEntity invoice = invoiceMapper.selectById(p.getInvoiceId());
        return new RunTarget(po.getPoNumber(), invoice.getInvoiceNumber(), p.getId());
    }

    @Override
    public void onRunCreated(RunTarget target, String runId) {
        if (paymentMapper.startExecuting(target.paymentOrderId(), runId) != 1) {
            // resolve 时已经锁住并确认是 PENDING，到这里不可能失败；失败说明有绕过锁的写入
            throw new IllegalStateException("付款单状态在加锁期间被修改: " + target.paymentOrderId());
        }
    }
}
