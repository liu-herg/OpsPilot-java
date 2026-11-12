package com.opspilot.procure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opspilot.gateway.Invoice;
import com.opspilot.gateway.Payable;
import com.opspilot.gateway.PayableSource;
import com.opspilot.store.InvoiceEntity;
import com.opspilot.store.InvoiceMapper;
import com.opspilot.store.PaymentOrderEntity;
import com.opspilot.store.PaymentOrderMapper;
import com.opspilot.store.PurchaseOrderEntity;
import com.opspilot.store.PurchaseOrderMapper;
import com.opspilot.store.SupplierEntity;
import com.opspilot.store.SupplierMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 付款依据来自系统内部单据：付款单（付给谁、付多少）和发票（发票上写的供应商、价税合计）。
 * 执行器拿这两份互相复核：正常情况下一定一致，不一致说明数据被改过或者有 bug，宁可失败也不付错。
 */
@Component
@Primary
public class LedgerPayableSource implements PayableSource {

    private final PurchaseOrderMapper orderMapper;
    private final InvoiceMapper invoiceMapper;
    private final PaymentOrderMapper paymentMapper;
    private final SupplierMapper supplierMapper;

    public LedgerPayableSource(PurchaseOrderMapper orderMapper, InvoiceMapper invoiceMapper,
                               PaymentOrderMapper paymentMapper, SupplierMapper supplierMapper) {
        this.orderMapper = orderMapper;
        this.invoiceMapper = invoiceMapper;
        this.paymentMapper = paymentMapper;
        this.supplierMapper = supplierMapper;
    }

    @Override
    public Optional<Payable> findPayable(String poNumber, String invoiceNumber) {
        return invoice(poNumber, invoiceNumber).flatMap(inv -> {
            PaymentOrderEntity p = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                    .eq(PaymentOrderEntity::getInvoiceId, inv.getId()));
            if (p == null) {
                return Optional.empty();
            }
            SupplierEntity supplier = supplierMapper.selectById(p.getSupplierId());
            return Optional.of(new Payable(poNumber, invoiceNumber, supplier.getName(), p.getAmount(), "CNY",
                    ProcureStatus.Supplier.ACTIVE.name().equals(supplier.getStatus())));
        });
    }

    @Override
    public Optional<Invoice> findInvoice(String poNumber, String invoiceNumber) {
        return invoice(poNumber, invoiceNumber).map(inv -> new Invoice(inv.getInvoiceNumber(), poNumber,
                supplierMapper.selectById(inv.getSupplierId()).getName(), inv.getTotalAmount(), inv.getStatus()));
    }

    private Optional<InvoiceEntity> invoice(String poNumber, String invoiceNumber) {
        if (invoiceNumber == null) {
            return Optional.empty();
        }
        PurchaseOrderEntity po = orderMapper.selectByNumber(poNumber);
        if (po == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(invoiceMapper.selectOne(new LambdaQueryWrapper<InvoiceEntity>()
                .eq(InvoiceEntity::getPoId, po.getId())
                .eq(InvoiceEntity::getInvoiceNumber, invoiceNumber)));
    }
}
