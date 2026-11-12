package com.opspilot.procure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.config.JsonCodec;
import com.opspilot.procure.ProcureStatus.Invoice;
import com.opspilot.procure.ProcureStatus.MatchException;
import com.opspilot.procure.ProcureStatus.Order;
import com.opspilot.store.InvoiceEntity;
import com.opspilot.store.InvoiceLineEntity;
import com.opspilot.store.InvoiceLineMapper;
import com.opspilot.store.InvoiceMapper;
import com.opspilot.store.MatchExceptionEntity;
import com.opspilot.store.MatchExceptionMapper;
import com.opspilot.store.PaymentOrderEntity;
import com.opspilot.store.PaymentOrderMapper;
import com.opspilot.store.PurchaseOrderEntity;
import com.opspilot.store.PurchaseOrderLineEntity;
import com.opspilot.store.PurchaseOrderLineMapper;
import com.opspilot.store.PurchaseOrderMapper;
import com.opspilot.store.SupplierMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发票：登记 + 三单匹配 + 差异处理。
 *
 * <p>和收货一样，所有会改"已开票数量"的操作都先锁订单（同一张订单串行），再用条件递增占用数量
 * （{@code invoiced_qty + ? <= received_qty}）。所以两张发票同时开同一批已收货的数量，只有一张能占到，
 * 另一张会按数量差异进入 EXCEPTION，不会同一批货付两次款。
 *
 * <ul>
 *   <li>匹配通过：占用开票数量 → 发票 PAYABLE → 生成付款单（PENDING），同一个事务</li>
 *   <li>有差异：发票 EXCEPTION + 差异单，不占用数量</li>
 *   <li>单价 / 合计差异可以由财务接受；全部接受后按"匹配通过"处理</li>
 *   <li>数量差异不能接受（等于为没到的货付钱）：补收货后重新匹配</li>
 * </ul>
 */
@Service
public class InvoiceService {

    private final InvoiceMapper invoiceMapper;
    private final InvoiceLineMapper lineMapper;
    private final MatchExceptionMapper exceptionMapper;
    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderLineMapper orderLineMapper;
    private final SupplierMapper supplierMapper;
    private final PaymentOrderMapper paymentMapper;
    private final PaymentOrderService payments;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final ThreeWayMatcher matcher;

    public InvoiceService(InvoiceMapper invoiceMapper, InvoiceLineMapper lineMapper,
                          MatchExceptionMapper exceptionMapper, PurchaseOrderMapper orderMapper,
                          PurchaseOrderLineMapper orderLineMapper, SupplierMapper supplierMapper,
                          PaymentOrderMapper paymentMapper, PaymentOrderService payments, TransactionTemplate tx,
                          JsonCodec json, @Value("${opspilot.match.price-tolerance:0.01}") BigDecimal priceTolerance) {
        this.invoiceMapper = invoiceMapper;
        this.lineMapper = lineMapper;
        this.exceptionMapper = exceptionMapper;
        this.orderMapper = orderMapper;
        this.orderLineMapper = orderLineMapper;
        this.supplierMapper = supplierMapper;
        this.paymentMapper = paymentMapper;
        this.payments = payments;
        this.tx = tx;
        this.json = json;
        this.matcher = new ThreeWayMatcher(priceTolerance);
    }

    // ---------------------------------------------------------------- 登记

    public InvoiceView register(ProcureCommands.RegisterInvoice cmd) {
        Long invoiceId = tx.execute(s -> {
            PurchaseOrderEntity po = lockOrderByNumber(cmd.poNumber().trim());
            if (!Order.PARTIALLY_RECEIVED.name().equals(po.getStatus()) && !Order.RECEIVED.name().equals(po.getStatus())) {
                throw new ConflictException("订单当前状态为 " + po.getStatus() + "，只有已收货的订单可以登记发票",
                        po.getStatus());
            }
            if (!po.getSupplierId().equals(cmd.supplierId())) {
                throw new ConflictException("发票的供应商和订单的供应商不一致", po.getStatus());
            }

            Map<Integer, PurchaseOrderLineEntity> orderLines = lockOrderLines(po.getId());
            List<ThreeWayMatcher.InvoiceLine> lines = parseLines(cmd.lines(), orderLines);
            BigDecimal amount = Money.nonNegative(cmd.amount(), "不含税金额");
            BigDecimal tax = Money.nonNegative(cmd.taxAmount(), "税额");
            BigDecimal total = Money.price(cmd.totalAmount(), "价税合计");
            List<ThreeWayMatcher.Mismatch> mismatches = matcher.match(toMatchLines(orderLines), lines, amount, tax, total);

            InvoiceEntity invoice = new InvoiceEntity();
            invoice.setInvoiceNumber(cmd.invoiceNumber().trim());
            invoice.setSupplierId(cmd.supplierId());
            invoice.setPoId(po.getId());
            invoice.setInvoiceDate(cmd.invoiceDate());
            invoice.setAmount(amount);
            invoice.setTaxAmount(tax);
            invoice.setTotalAmount(total);
            invoice.setStatus(mismatches.isEmpty() ? Invoice.PAYABLE.name() : Invoice.EXCEPTION.name());
            invoice.setRegisteredBy(cmd.registeredBy().trim());
            LocalDateTime now = LocalDateTime.now();
            invoice.setCreatedAt(now);
            invoice.setUpdatedAt(now);
            try {
                invoiceMapper.insert(invoice);
            } catch (DuplicateKeyException e) {
                throw new ConflictException("发票 " + invoice.getInvoiceNumber() + " 已经登记过", null);
            }

            List<InvoiceLineEntity> saved = new ArrayList<>();
            for (ThreeWayMatcher.InvoiceLine l : lines) {
                InvoiceLineEntity e = new InvoiceLineEntity();
                e.setInvoiceId(invoice.getId());
                e.setPoLineId(orderLines.get(l.lineNo()).getId());
                e.setQuantity(l.quantity());
                e.setUnitPrice(l.unitPrice());
                e.setAmount(l.amount());
                e.setOccupied(false);
                lineMapper.insert(e);
                saved.add(e);
            }

            if (mismatches.isEmpty()) {
                occupyAndCreatePayment(invoice, saved);
            } else {
                saveMismatches(invoice.getId(), mismatches, orderLines);
            }
            return invoice.getId();
        });
        return get(invoiceId);
    }

    // ---------------------------------------------------------------- 差异处理

    /** 接受一条单价 / 合计差异。发票的差异全部处理完，就按匹配通过处理（占用数量、生成付款单）。 */
    public InvoiceView acceptException(Long exceptionId, String actor) {
        MatchExceptionEntity exc = requireException(exceptionId);
        if (ThreeWayMatcher.QTY_EXCEEDS_RECEIVED.equals(exc.getType())) {
            throw new ConflictException("数量差异不能直接接受：请先补收货，再对发票重新匹配", exc.getStatus());
        }
        tx.executeWithoutResult(s -> {
            InvoiceEntity invoice = lockInvoiceWithOrder(exc.getInvoiceId());
            requireStatus(invoice, Invoice.EXCEPTION);
            if (exceptionMapper.resolve(exceptionId, MatchException.ACCEPTED.name(), actor.trim()) == 0) {
                throw new ConflictException("差异已经处理过了", requireException(exceptionId).getStatus());
            }
            if (exceptionMapper.countOpen(invoice.getId()) == 0) {
                occupyAndCreatePayment(invoice, lineMapper.selectByInvoice(invoice.getId()));
                invoiceMapper.transition(invoice.getId(), Invoice.EXCEPTION.name(), Invoice.PAYABLE.name());
            }
        });
        return get(exc.getInvoiceId());
    }

    /** 驳回：发票作废（REJECTED），其余未处理的差异一起关闭。 */
    public InvoiceView rejectException(Long exceptionId, String actor) {
        MatchExceptionEntity exc = requireException(exceptionId);
        tx.executeWithoutResult(s -> {
            InvoiceEntity invoice = lockInvoiceWithOrder(exc.getInvoiceId());
            requireStatus(invoice, Invoice.EXCEPTION);
            if (exceptionMapper.resolve(exceptionId, MatchException.REJECTED.name(), actor.trim()) == 0) {
                throw new ConflictException("差异已经处理过了", requireException(exceptionId).getStatus());
            }
            exceptionMapper.resolveAllOpen(invoice.getId(), MatchException.REJECTED.name(), actor.trim());
            invoiceMapper.transition(invoice.getId(), Invoice.EXCEPTION.name(), Invoice.REJECTED.name());
        });
        return get(exc.getInvoiceId());
    }

    /**
     * 重新匹配（典型场景：数量差异 → 补收货之后）。已经被接受的差异不再算；
     * 没有新差异 → 未处理的差异标记 RESOLVED，按匹配通过处理；还有差异 → 用新的差异单替换旧的。
     */
    public InvoiceView rematch(Long invoiceId, String actor) {
        tx.executeWithoutResult(s -> {
            InvoiceEntity invoice = lockInvoiceWithOrder(invoiceId);
            requireStatus(invoice, Invoice.EXCEPTION);
            Map<Integer, PurchaseOrderLineEntity> orderLines = lockOrderLines(invoice.getPoId());
            Map<Long, Integer> lineNoById = orderLines.values().stream()
                    .collect(Collectors.toMap(PurchaseOrderLineEntity::getId, PurchaseOrderLineEntity::getLineNo));
            List<InvoiceLineEntity> saved = lineMapper.selectByInvoice(invoiceId);
            List<ThreeWayMatcher.InvoiceLine> lines = saved.stream()
                    .map(l -> new ThreeWayMatcher.InvoiceLine(lineNoById.get(l.getPoLineId()), l.getQuantity(),
                            l.getUnitPrice(), l.getAmount()))
                    .toList();

            Set<String> accepted = new HashSet<>();
            for (MatchExceptionEntity e : exceptionMapper.selectByInvoice(invoiceId)) {
                if (MatchException.ACCEPTED.name().equals(e.getStatus())) {
                    accepted.add(e.getType() + "|" + e.getPoLineId());
                }
            }
            List<ThreeWayMatcher.Mismatch> remaining = matcher.match(toMatchLines(orderLines), lines,
                            invoice.getAmount(), invoice.getTaxAmount(), invoice.getTotalAmount()).stream()
                    .filter(m -> !accepted.contains(m.type() + "|" + poLineId(m, orderLines)))
                    .toList();

            exceptionMapper.resolveAllOpen(invoiceId, MatchException.RESOLVED.name(), actor.trim());
            if (remaining.isEmpty()) {
                occupyAndCreatePayment(invoice, saved);
                invoiceMapper.transition(invoiceId, Invoice.EXCEPTION.name(), Invoice.PAYABLE.name());
            } else {
                saveMismatches(invoiceId, remaining, orderLines);
            }
        });
        return get(invoiceId);
    }

    // ---------------------------------------------------------------- 查询

    public InvoiceView get(Long invoiceId) {
        InvoiceEntity invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            throw new NotFoundException("发票不存在: " + invoiceId);
        }
        Map<Long, Integer> lineNoById = orderLineMapper.selectByOrder(invoice.getPoId()).stream()
                .collect(Collectors.toMap(PurchaseOrderLineEntity::getId, PurchaseOrderLineEntity::getLineNo));
        List<InvoiceView.Line> lines = lineMapper.selectByInvoice(invoiceId).stream()
                .map(l -> new InvoiceView.Line(lineNoById.get(l.getPoLineId()), l.getQuantity(), l.getUnitPrice(),
                        l.getAmount()))
                .toList();
        List<InvoiceView.Mismatch> exceptions = exceptionMapper.selectByInvoice(invoiceId).stream()
                .map(e -> new InvoiceView.Mismatch(e.getId(), e.getPoLineId() == null ? null : lineNoById.get(e.getPoLineId()),
                        e.getType(), json.readMap(e.getDetail()), e.getStatus(), e.getResolver(), e.getResolvedAt()))
                .toList();
        return view(invoice, lines, exceptions);
    }

    public PageResult<InvoiceView> list(String status, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        LambdaQueryWrapper<InvoiceEntity> where = new LambdaQueryWrapper<InvoiceEntity>()
                .eq(status != null && !status.isBlank(), InvoiceEntity::getStatus,
                        status == null ? null : status.trim().toUpperCase());
        long total = invoiceMapper.selectCount(where);
        where.orderByDesc(InvoiceEntity::getId).last(PageResult.limit(p, s));
        List<InvoiceView> items = invoiceMapper.selectList(where).stream().map(i -> view(i, null, null)).toList();
        return new PageResult<>(items, total, p, s);
    }

    /** 待处理（或指定状态）的差异单，带上发票号，方便财务逐条处理。 */
    public List<Map<String, Object>> listExceptions(String status) {
        String st = status == null || status.isBlank() ? MatchException.OPEN.name() : status.trim().toUpperCase();
        List<MatchExceptionEntity> rows = exceptionMapper.selectList(new LambdaQueryWrapper<MatchExceptionEntity>()
                .eq(MatchExceptionEntity::getStatus, st).orderByDesc(MatchExceptionEntity::getId).last("LIMIT 100"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (MatchExceptionEntity e : rows) {
            InvoiceEntity invoice = invoiceMapper.selectById(e.getInvoiceId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("invoice_id", e.getInvoiceId());
            m.put("invoice_number", invoice == null ? null : invoice.getInvoiceNumber());
            m.put("type", e.getType());
            m.put("detail", json.readMap(e.getDetail()));
            m.put("status", e.getStatus());
            m.put("created_at", e.getCreatedAt());
            result.add(m);
        }
        return result;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 占用开票数量 + 生成付款单。调用方已经锁住订单，所以条件递增一定成功；
     * 万一失败（数量被别的入口占了），整个事务回滚，宁可报错也不多付。
     */
    private void occupyAndCreatePayment(InvoiceEntity invoice, List<InvoiceLineEntity> lines) {
        List<InvoiceLineEntity> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> a.getPoLineId().compareTo(b.getPoLineId()));
        for (InvoiceLineEntity l : sorted) {
            if (Boolean.TRUE.equals(l.getOccupied())) {
                continue;
            }
            if (orderLineMapper.occupyInvoiced(l.getPoLineId(), l.getQuantity()) == 0) {
                throw new ConflictException("可开票数量不足（已收货的数量已经被其他发票占用）", null);
            }
            lineMapper.markOccupied(l.getId());
        }
        payments.createFor(invoice);
    }

    private void saveMismatches(Long invoiceId, List<ThreeWayMatcher.Mismatch> mismatches,
                                Map<Integer, PurchaseOrderLineEntity> orderLines) {
        LocalDateTime now = LocalDateTime.now();
        for (ThreeWayMatcher.Mismatch m : mismatches) {
            MatchExceptionEntity e = new MatchExceptionEntity();
            e.setInvoiceId(invoiceId);
            e.setPoLineId(poLineId(m, orderLines));
            e.setType(m.type());
            e.setDetail(json.write(m.detail()));
            e.setStatus(MatchException.OPEN.name());
            e.setCreatedAt(now);
            exceptionMapper.insert(e);
        }
    }

    private static Long poLineId(ThreeWayMatcher.Mismatch m, Map<Integer, PurchaseOrderLineEntity> orderLines) {
        return m.lineNo() == null ? null : orderLines.get(m.lineNo()).getId();
    }

    private List<ThreeWayMatcher.InvoiceLine> parseLines(List<ProcureCommands.InvoiceLine> in,
                                                         Map<Integer, PurchaseOrderLineEntity> orderLines) {
        Set<Integer> seen = new HashSet<>();
        List<ThreeWayMatcher.InvoiceLine> lines = new ArrayList<>();
        for (ProcureCommands.InvoiceLine l : in) {
            if (!orderLines.containsKey(l.lineNo())) {
                throw new IllegalArgumentException("订单里没有第 " + l.lineNo() + " 行");
            }
            if (!seen.add(l.lineNo())) {
                throw new IllegalArgumentException("第 " + l.lineNo() + " 行重复出现");
            }
            BigDecimal qty = Money.quantity(l.quantity(), "第 " + l.lineNo() + " 行数量");
            BigDecimal price = Money.price(l.unitPrice(), "第 " + l.lineNo() + " 行单价");
            lines.add(new ThreeWayMatcher.InvoiceLine(l.lineNo(), qty, price, Money.amount(qty, price)));
        }
        return lines;
    }

    private static Map<Integer, ThreeWayMatcher.OrderLine> toMatchLines(Map<Integer, PurchaseOrderLineEntity> lines) {
        Map<Integer, ThreeWayMatcher.OrderLine> result = new LinkedHashMap<>();
        lines.forEach((no, l) -> result.put(no, new ThreeWayMatcher.OrderLine(l.getId(), no, l.getUnitPrice(),
                l.getReceivedQty(), l.getInvoicedQty())));
        return result;
    }

    /** 当前读订单明细（见 PurchaseOrderLineMapper.lockByOrder 的说明）。 */
    private Map<Integer, PurchaseOrderLineEntity> lockOrderLines(Long poId) {
        return orderLineMapper.lockByOrder(poId).stream()
                .collect(Collectors.toMap(PurchaseOrderLineEntity::getLineNo, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private PurchaseOrderEntity lockOrderByNumber(String poNumber) {
        PurchaseOrderEntity po = orderMapper.lockByNumber(poNumber);
        if (po == null) {
            throw new NotFoundException("采购订单不存在: " + poNumber);
        }
        return po;
    }

    /** 加锁顺序和收货一致：先订单，再发票。 */
    private InvoiceEntity lockInvoiceWithOrder(Long invoiceId) {
        InvoiceEntity plain = invoiceMapper.selectById(invoiceId);
        if (plain == null) {
            throw new NotFoundException("发票不存在: " + invoiceId);
        }
        orderMapper.lockById(plain.getPoId());
        return invoiceMapper.lockById(invoiceId);
    }

    private static void requireStatus(InvoiceEntity invoice, Invoice expected) {
        if (!expected.name().equals(invoice.getStatus())) {
            throw new ConflictException("发票当前状态为 " + invoice.getStatus(), invoice.getStatus());
        }
    }

    private MatchExceptionEntity requireException(Long id) {
        MatchExceptionEntity e = id == null ? null : exceptionMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("差异单不存在: " + id);
        }
        return e;
    }

    private InvoiceView view(InvoiceEntity invoice, List<InvoiceView.Line> lines, List<InvoiceView.Mismatch> exceptions) {
        PurchaseOrderEntity po = orderMapper.selectById(invoice.getPoId());
        PaymentOrderEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getInvoiceId, invoice.getId()));
        return new InvoiceView(invoice.getId(), invoice.getInvoiceNumber(), po == null ? null : po.getPoNumber(),
                PurchaseOrderView.SupplierRef.of(supplierMapper.selectById(invoice.getSupplierId())),
                invoice.getInvoiceDate(), invoice.getAmount(), invoice.getTaxAmount(), invoice.getTotalAmount(),
                invoice.getStatus(), invoice.getRegisteredBy(), invoice.getCreatedAt(),
                payment == null ? null : payment.getPayNumber(), lines, exceptions);
    }
}
