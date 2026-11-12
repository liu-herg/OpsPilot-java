package com.opspilot.procure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.procure.ProcureStatus.Payment;
import com.opspilot.run.RunService;
import com.opspilot.run.TaskCreated;
import com.opspilot.store.InvoiceEntity;
import com.opspilot.store.InvoiceMapper;
import com.opspilot.store.PaymentOrderEntity;
import com.opspilot.store.PaymentOrderMapper;
import com.opspilot.store.PurchaseOrderEntity;
import com.opspilot.store.PurchaseOrderMapper;
import com.opspilot.store.SupplierEntity;
import com.opspilot.store.SupplierMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 付款单：发票匹配通过后生成（PENDING），财务发起后交给执行引擎（一张付款单对应一次运行），
 * 运行结束时由 {@link PaymentOrderSync} 同步成 PAID / FAILED / REJECTED。
 */
@Service
public class PaymentOrderService {

    private final PaymentOrderMapper mapper;
    private final InvoiceMapper invoiceMapper;
    private final PurchaseOrderMapper orderMapper;
    private final SupplierMapper supplierMapper;
    private final BizNumberService numbers;
    private final RunService runService;

    public PaymentOrderService(PaymentOrderMapper mapper, InvoiceMapper invoiceMapper, PurchaseOrderMapper orderMapper,
                               SupplierMapper supplierMapper, BizNumberService numbers, RunService runService) {
        this.mapper = mapper;
        this.invoiceMapper = invoiceMapper;
        this.orderMapper = orderMapper;
        this.supplierMapper = supplierMapper;
        this.numbers = numbers;
        this.runService = runService;
    }

    /**
     * 为一张匹配通过的发票生成付款单。必须在登记 / 处理发票的事务里调用。
     * invoice_id 有唯一索引：同一张发票不会有第二张付款单。
     */
    PaymentOrderEntity createFor(InvoiceEntity invoice) {
        PaymentOrderEntity p = new PaymentOrderEntity();
        p.setPayNumber(numbers.next("PAY"));
        p.setInvoiceId(invoice.getId());
        p.setPoId(invoice.getPoId());
        p.setSupplierId(invoice.getSupplierId());
        p.setAmount(invoice.getTotalAmount());
        p.setStatus(Payment.PENDING.name());
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        try {
            mapper.insert(p);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("发票已经生成过付款单", null);
        }
        return p;
    }

    /**
     * 发起付款：创建运行（同一个事务里把付款单 PENDING → EXECUTING）并启动。
     * 并发发起同一张付款单，只有一个能建出运行，其余返回 409。
     */
    public PaymentOrderView pay(Long id) {
        PaymentOrderEntity p = require(id);
        PurchaseOrderEntity po = orderMapper.selectById(p.getPoId());
        TaskCreated created = runService.createRun("发起付款 " + p.getPayNumber(), po.getPoNumber(), id);
        runService.start(created.runId());
        return get(id);
    }

    /** 付款失败后重新发起：FAILED → PENDING，再走一遍 {@link #pay}（新的运行）。 */
    public PaymentOrderView retry(Long id) {
        if (mapper.reopen(id) == 0) {
            PaymentOrderEntity p = require(id);
            throw new ConflictException("付款单当前状态为 " + p.getStatus() + "，只有失败的付款单可以重新发起",
                    p.getStatus());
        }
        return pay(id);
    }

    public PaymentOrderView get(Long id) {
        return view(require(id));
    }

    public PageResult<PaymentOrderView> list(String status, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        LambdaQueryWrapper<PaymentOrderEntity> where = new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(status != null && !status.isBlank(), PaymentOrderEntity::getStatus,
                        status == null ? null : status.trim().toUpperCase());
        long total = mapper.selectCount(where);
        where.orderByDesc(PaymentOrderEntity::getId).last(PageResult.limit(p, s));
        List<PaymentOrderView> items = mapper.selectList(where).stream().map(this::view).toList();
        return new PageResult<>(items, total, p, s);
    }

    PaymentOrderView view(PaymentOrderEntity p) {
        PurchaseOrderEntity po = orderMapper.selectById(p.getPoId());
        InvoiceEntity invoice = invoiceMapper.selectById(p.getInvoiceId());
        SupplierEntity supplier = supplierMapper.selectById(p.getSupplierId());
        return new PaymentOrderView(p.getId(), p.getPayNumber(), po == null ? null : po.getPoNumber(),
                invoice == null ? null : invoice.getInvoiceNumber(), PurchaseOrderView.SupplierRef.of(supplier),
                p.getAmount(), p.getStatus(), p.getRunId(), p.getFinancePaymentId(), p.getCreatedAt());
    }

    private PaymentOrderEntity require(Long id) {
        PaymentOrderEntity p = id == null ? null : mapper.selectById(id);
        if (p == null) {
            throw new NotFoundException("付款单不存在: " + id);
        }
        return p;
    }
}
