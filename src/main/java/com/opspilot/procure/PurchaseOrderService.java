package com.opspilot.procure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.procure.ProcureStatus.Order;
import com.opspilot.store.GoodsReceiptEntity;
import com.opspilot.store.GoodsReceiptLineEntity;
import com.opspilot.store.GoodsReceiptLineMapper;
import com.opspilot.store.GoodsReceiptMapper;
import com.opspilot.store.PurchaseOrderEntity;
import com.opspilot.store.PurchaseOrderLineEntity;
import com.opspilot.store.PurchaseOrderLineMapper;
import com.opspilot.store.PurchaseOrderMapper;
import com.opspilot.store.SupplierEntity;
import com.opspilot.store.SupplierMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 采购订单：查询、取消、收货。
 *
 * <p>收货的并发控制分两层：
 * <ol>
 *   <li>先锁订单（{@code SELECT ... FOR UPDATE}）：同一张订单的收货、取消串行，订单状态（部分收货 / 收齐）不会算错；
 *       不同订单之间互不影响</li>
 *   <li>每一行用条件递增（{@code received_qty + ? <= quantity}）：数量上限由数据库判断，
 *       哪怕以后有别的入口绕过了订单锁，也不会超收</li>
 * </ol>
 * 加锁顺序固定为"订单 → 明细（按 id 从小到大）"，不会死锁。
 */
@Service
public class PurchaseOrderService {

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderLineMapper lineMapper;
    private final GoodsReceiptMapper receiptMapper;
    private final GoodsReceiptLineMapper receiptLineMapper;
    private final SupplierMapper supplierMapper;
    private final BizNumberService numbers;
    private final TransactionTemplate tx;

    public PurchaseOrderService(PurchaseOrderMapper orderMapper, PurchaseOrderLineMapper lineMapper,
                                GoodsReceiptMapper receiptMapper, GoodsReceiptLineMapper receiptLineMapper,
                                SupplierMapper supplierMapper, BizNumberService numbers, TransactionTemplate tx) {
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.receiptMapper = receiptMapper;
        this.receiptLineMapper = receiptLineMapper;
        this.supplierMapper = supplierMapper;
        this.numbers = numbers;
        this.tx = tx;
    }

    /** 收货的结果：本次收货单号 + 收货后的订单。 */
    public record ReceiptResult(String grNumber, PurchaseOrderView order) {
    }

    public ReceiptResult receive(Long poId, ProcureCommands.CreateReceipt cmd) {
        String grNumber = tx.execute(s -> {
            PurchaseOrderEntity po = lockOrder(poId);
            Order status = Order.valueOf(po.getStatus());
            if (status != Order.ISSUED && status != Order.PARTIALLY_RECEIVED) {
                throw new ConflictException("订单当前状态为 " + status + "，不能收货", status.name());
            }

            // 当前读：不依赖"加锁之前没做过普通查询"这个前提
            Map<Integer, PurchaseOrderLineEntity> byLineNo = lineMapper.lockByOrder(poId).stream()
                    .collect(Collectors.toMap(PurchaseOrderLineEntity::getLineNo, Function.identity()));
            Set<Integer> seen = new HashSet<>();
            List<Planned> planned = new ArrayList<>();
            for (ProcureCommands.ReceiptLine in : cmd.lines()) {
                PurchaseOrderLineEntity line = byLineNo.get(in.lineNo());
                if (line == null) {
                    throw new IllegalArgumentException("订单里没有第 " + in.lineNo() + " 行");
                }
                if (!seen.add(in.lineNo())) {
                    throw new IllegalArgumentException("第 " + in.lineNo() + " 行重复出现");
                }
                planned.add(new Planned(line, Money.quantity(in.quantity(), "第 " + in.lineNo() + " 行收货数量")));
            }

            GoodsReceiptEntity gr = new GoodsReceiptEntity();
            gr.setGrNumber(numbers.next("GR"));
            gr.setPoId(poId);
            gr.setReceiver(cmd.receiver().trim());
            gr.setCreatedAt(LocalDateTime.now());
            receiptMapper.insert(gr);

            planned.sort(Comparator.comparing(p -> p.line().getId()));
            for (Planned p : planned) {
                if (lineMapper.receive(p.line().getId(), poId, p.quantity()) == 0) {
                    PurchaseOrderLineEntity line = p.line();
                    throw new ConflictException("第 " + line.getLineNo() + " 行超收：订购 " + line.getQuantity()
                            + "，已收 " + line.getReceivedQty() + "，本次 " + p.quantity(), status.name());
                }
                GoodsReceiptLineEntity gl = new GoodsReceiptLineEntity();
                gl.setGrId(gr.getId());
                gl.setPoLineId(p.line().getId());
                gl.setQuantity(p.quantity());
                receiptLineMapper.insert(gl);
            }

            Order next = lineMapper.fullyReceived(poId) ? Order.RECEIVED : Order.PARTIALLY_RECEIVED;
            if (next != status) {
                // 订单已经被我们锁住，这里的条件更新一定成功
                orderMapper.transition(poId, status.name(), next.name());
            }
            return gr.getGrNumber();
        });
        return new ReceiptResult(grNumber, get(poId));
    }

    private record Planned(PurchaseOrderLineEntity line, BigDecimal quantity) {
    }

    /** 取消：只有还没收过货（ISSUED）的订单能取消。和收货一样先锁订单，避免"边收货边取消"。 */
    public PurchaseOrderView cancel(Long poId) {
        tx.executeWithoutResult(s -> {
            PurchaseOrderEntity po = lockOrder(poId);
            if (!Order.ISSUED.name().equals(po.getStatus())) {
                throw new ConflictException("订单当前状态为 " + po.getStatus() + "，只有未收货的订单可以取消",
                        po.getStatus());
            }
            orderMapper.transition(poId, Order.ISSUED.name(), Order.CANCELLED.name());
        });
        return get(poId);
    }

    public PurchaseOrderView get(Long poId) {
        PurchaseOrderEntity po = orderMapper.selectById(poId);
        if (po == null) {
            throw new NotFoundException("采购订单不存在: " + poId);
        }
        return detail(po);
    }

    public PurchaseOrderView getByNumber(String poNumber) {
        PurchaseOrderEntity po = orderMapper.selectByNumber(poNumber);
        if (po == null) {
            throw new NotFoundException("采购订单不存在: " + poNumber);
        }
        return detail(po);
    }

    public PageResult<PurchaseOrderView> list(String status, Long supplierId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        LambdaQueryWrapper<PurchaseOrderEntity> where = new LambdaQueryWrapper<PurchaseOrderEntity>()
                .eq(status != null && !status.isBlank(), PurchaseOrderEntity::getStatus,
                        status == null ? null : status.trim().toUpperCase())
                .eq(supplierId != null, PurchaseOrderEntity::getSupplierId, supplierId);
        long total = orderMapper.selectCount(where);
        where.orderByDesc(PurchaseOrderEntity::getId).last(PageResult.limit(p, s));
        List<PurchaseOrderEntity> rows = orderMapper.selectList(where);
        Map<Long, SupplierEntity> suppliers = rows.isEmpty() ? Map.of()
                : supplierMapper.selectList(new LambdaQueryWrapper<SupplierEntity>().in(SupplierEntity::getId,
                        rows.stream().map(PurchaseOrderEntity::getSupplierId).distinct().toList()))
                .stream().collect(Collectors.toMap(SupplierEntity::getId, Function.identity()));
        List<PurchaseOrderView> items = rows.stream()
                .map(o -> PurchaseOrderView.of(o, suppliers.get(o.getSupplierId()), null, null))
                .toList();
        return new PageResult<>(items, total, p, s);
    }

    private PurchaseOrderView detail(PurchaseOrderEntity po) {
        List<PurchaseOrderLineEntity> lines = lineMapper.selectByOrder(po.getId());
        Map<Long, Integer> lineNoById = lines.stream()
                .collect(Collectors.toMap(PurchaseOrderLineEntity::getId, PurchaseOrderLineEntity::getLineNo));
        List<PurchaseOrderView.Receipt> receipts = receiptMapper.selectByOrder(po.getId()).stream()
                .map(gr -> PurchaseOrderView.receipt(gr, receiptLineMapper.selectByReceipt(gr.getId()).stream()
                        .map(gl -> new PurchaseOrderView.ReceiptLine(lineNoById.get(gl.getPoLineId()), gl.getQuantity()))
                        .toList()))
                .toList();
        return PurchaseOrderView.of(po, supplierMapper.selectById(po.getSupplierId()), lines, receipts);
    }

    private PurchaseOrderEntity lockOrder(Long poId) {
        PurchaseOrderEntity po = orderMapper.lockById(poId);
        if (po == null) {
            throw new NotFoundException("采购订单不存在: " + poId);
        }
        return po;
    }
}
