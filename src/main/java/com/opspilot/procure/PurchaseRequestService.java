package com.opspilot.procure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.procure.ProcureStatus.Request;
import com.opspilot.store.PurchaseOrderEntity;
import com.opspilot.store.PurchaseOrderLineEntity;
import com.opspilot.store.PurchaseOrderLineMapper;
import com.opspilot.store.PurchaseOrderMapper;
import com.opspilot.store.PurchaseRequestEntity;
import com.opspilot.store.PurchaseRequestLineEntity;
import com.opspilot.store.PurchaseRequestLineMapper;
import com.opspilot.store.PurchaseRequestMapper;
import com.opspilot.store.SupplierEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购申请：草稿 → 提交 → 批准 / 驳回 → 转采购订单。
 * P4 是单级审批（审批人不能是申请人本人），P5 换成按金额、部门配置的多级审批。
 */
@Service
public class PurchaseRequestService {

    private final PurchaseRequestMapper requestMapper;
    private final PurchaseRequestLineMapper lineMapper;
    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderLineMapper orderLineMapper;
    private final SupplierService suppliers;
    private final PurchaseOrderService orders;
    private final BizNumberService numbers;
    private final TransactionTemplate tx;

    public PurchaseRequestService(PurchaseRequestMapper requestMapper, PurchaseRequestLineMapper lineMapper,
                                  PurchaseOrderMapper orderMapper, PurchaseOrderLineMapper orderLineMapper,
                                  SupplierService suppliers, PurchaseOrderService orders, BizNumberService numbers,
                                  TransactionTemplate tx) {
        this.requestMapper = requestMapper;
        this.lineMapper = lineMapper;
        this.orderMapper = orderMapper;
        this.orderLineMapper = orderLineMapper;
        this.suppliers = suppliers;
        this.orders = orders;
        this.numbers = numbers;
        this.tx = tx;
    }

    /** 新建草稿：头和明细在一个事务里写入，金额按"数量 × 预估单价"计算。 */
    public PurchaseRequestView create(ProcureCommands.CreateRequest cmd) {
        Long id = tx.execute(s -> {
            PurchaseRequestEntity r = new PurchaseRequestEntity();
            r.setRequestNo(numbers.next("PR"));
            r.setDepartment(cmd.department().trim());
            r.setRequester(cmd.requester().trim());
            r.setTitle(cmd.title().trim());
            r.setStatus(Request.DRAFT.name());
            LocalDateTime now = LocalDateTime.now();
            r.setCreatedAt(now);
            r.setUpdatedAt(now);

            BigDecimal total = BigDecimal.ZERO;
            List<PurchaseRequestLineEntity> lines = new java.util.ArrayList<>();
            int lineNo = 1;
            for (ProcureCommands.RequestLine in : cmd.lines()) {
                PurchaseRequestLineEntity l = new PurchaseRequestLineEntity();
                l.setLineNo(lineNo);
                l.setItemName(in.itemName().trim());
                l.setSpec(in.spec() == null ? null : in.spec().trim());
                l.setQuantity(Money.quantity(in.quantity(), "第 " + lineNo + " 行数量"));
                l.setUnit(in.unit().trim());
                l.setEstUnitPrice(Money.price(in.estUnitPrice(), "第 " + lineNo + " 行预估单价"));
                l.setAmount(Money.amount(l.getQuantity(), l.getEstUnitPrice()));
                total = total.add(l.getAmount());
                lines.add(l);
                lineNo++;
            }
            r.setTotalAmount(total);
            requestMapper.insert(r);
            for (PurchaseRequestLineEntity l : lines) {
                l.setRequestId(r.getId());
                lineMapper.insert(l);
            }
            return r.getId();
        });
        return get(id);
    }

    public PurchaseRequestView submit(Long id) {
        move(id, List.of(Request.DRAFT), Request.SUBMITTED, null, "提交");
        return get(id);
    }

    public PurchaseRequestView approve(Long id, String actor) {
        PurchaseRequestEntity r = require(id);
        if (r.getRequester().equals(actor.trim())) {
            throw new IllegalArgumentException("不能审批自己提交的申请");
        }
        move(id, List.of(Request.SUBMITTED), Request.APPROVED, actor.trim(), "批准");
        return get(id);
    }

    public PurchaseRequestView reject(Long id, String actor) {
        move(id, List.of(Request.SUBMITTED), Request.REJECTED, actor.trim(), "驳回");
        return get(id);
    }

    public PurchaseRequestView cancel(Long id) {
        move(id, List.of(Request.DRAFT, Request.SUBMITTED), Request.CANCELLED, null, "撤销");
        return get(id);
    }

    /**
     * 转采购订单。一个事务里：申请 APPROVED → ORDERED（条件更新，并发转单只有一个成功）、
     * 校验供应商没被冻结、写订单头和明细。另外 purchase_order.request_id 有唯一索引兜底。
     */
    public PurchaseOrderView toOrder(Long id, ProcureCommands.CreateOrder cmd) {
        return toOrder(id, cmd, null);
    }

    /** fixedPoNumber 只给演示数据用（沿用 PO-10001 这样的老单号），正常都是系统取号。 */
    PurchaseOrderView toOrder(Long id, ProcureCommands.CreateOrder cmd, String fixedPoNumber) {
        Long poId = tx.execute(s -> {
            SupplierEntity supplier = suppliers.requireActive(cmd.supplierId());
            move(id, List.of(Request.APPROVED), Request.ORDERED, null, "转订单");

            Map<Integer, BigDecimal> overrides = new HashMap<>();
            if (cmd.prices() != null) {
                for (ProcureCommands.PriceOverride p : cmd.prices()) {
                    overrides.put(p.lineNo(), Money.price(p.unitPrice(), "第 " + p.lineNo() + " 行单价"));
                }
            }
            List<PurchaseRequestLineEntity> lines = lineMapper.selectByRequest(id);
            for (Integer lineNo : overrides.keySet()) {
                if (lines.stream().noneMatch(l -> l.getLineNo().equals(lineNo))) {
                    throw new IllegalArgumentException("申请里没有第 " + lineNo + " 行");
                }
            }

            PurchaseOrderEntity po = new PurchaseOrderEntity();
            po.setPoNumber(fixedPoNumber != null ? fixedPoNumber : numbers.next("PO"));
            po.setRequestId(id);
            po.setSupplierId(supplier.getId());
            po.setBuyer(cmd.buyer().trim());
            po.setStatus(ProcureStatus.Order.ISSUED.name());
            LocalDateTime now = LocalDateTime.now();
            po.setCreatedAt(now);
            po.setUpdatedAt(now);

            BigDecimal total = BigDecimal.ZERO;
            List<PurchaseOrderLineEntity> orderLines = new java.util.ArrayList<>();
            for (PurchaseRequestLineEntity l : lines) {
                PurchaseOrderLineEntity ol = new PurchaseOrderLineEntity();
                ol.setLineNo(l.getLineNo());
                ol.setItemName(l.getItemName());
                ol.setUnit(l.getUnit());
                ol.setQuantity(l.getQuantity());
                ol.setUnitPrice(overrides.getOrDefault(l.getLineNo(), l.getEstUnitPrice()));
                ol.setAmount(Money.amount(ol.getQuantity(), ol.getUnitPrice()));
                ol.setReceivedQty(BigDecimal.ZERO);
                ol.setInvoicedQty(BigDecimal.ZERO);
                total = total.add(ol.getAmount());
                orderLines.add(ol);
            }
            po.setTotalAmount(total);
            orderMapper.insert(po);
            for (PurchaseOrderLineEntity ol : orderLines) {
                ol.setPoId(po.getId());
                orderLineMapper.insert(ol);
            }
            return po.getId();
        });
        return orders.get(poId);
    }

    public PurchaseRequestView get(Long id) {
        PurchaseRequestEntity r = require(id);
        return PurchaseRequestView.of(r, lineMapper.selectByRequest(id), poNumberOf(id));
    }

    public PageResult<PurchaseRequestView> list(String status, String department, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        LambdaQueryWrapper<PurchaseRequestEntity> where = new LambdaQueryWrapper<PurchaseRequestEntity>()
                .eq(status != null && !status.isBlank(), PurchaseRequestEntity::getStatus,
                        status == null ? null : status.trim().toUpperCase())
                .eq(department != null && !department.isBlank(), PurchaseRequestEntity::getDepartment,
                        department == null ? null : department.trim());
        long total = requestMapper.selectCount(where);
        where.orderByDesc(PurchaseRequestEntity::getId).last(PageResult.limit(p, s));
        List<PurchaseRequestView> items = requestMapper.selectList(where).stream()
                .map(r -> PurchaseRequestView.of(r, null, null))
                .toList();
        return new PageResult<>(items, total, p, s);
    }

    private void move(Long id, List<Request> from, Request to, String decidedBy, String action) {
        List<String> fromNames = from.stream().map(Enum::name).toList();
        if (requestMapper.transition(id, fromNames, to.name(), decidedBy) == 0) {
            PurchaseRequestEntity current = require(id);
            throw new ConflictException("采购申请当前状态为 " + current.getStatus() + "，不能" + action,
                    current.getStatus());
        }
    }

    private String poNumberOf(Long requestId) {
        PurchaseOrderEntity po = orderMapper.selectOne(new LambdaQueryWrapper<PurchaseOrderEntity>()
                .eq(PurchaseOrderEntity::getRequestId, requestId));
        return po == null ? null : po.getPoNumber();
    }

    private PurchaseRequestEntity require(Long id) {
        PurchaseRequestEntity r = id == null ? null : requestMapper.selectById(id);
        if (r == null) {
            throw new NotFoundException("采购申请不存在: " + id);
        }
        return r;
    }
}
