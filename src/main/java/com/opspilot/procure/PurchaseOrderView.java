package com.opspilot.procure;

import com.opspilot.store.GoodsReceiptEntity;
import com.opspilot.store.PurchaseOrderEntity;
import com.opspilot.store.PurchaseOrderLineEntity;
import com.opspilot.store.SupplierEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 采购订单：明细（订购 / 已收 / 已开票）+ 收货记录。列表接口不带明细和收货（为 null）。 */
public record PurchaseOrderView(Long id, String poNumber, Long requestId, SupplierRef supplier, String buyer,
                                BigDecimal totalAmount, String status, LocalDateTime createdAt,
                                List<Line> lines, List<Receipt> receipts) {

    public record SupplierRef(Long id, String code, String name) {
        static SupplierRef of(SupplierEntity s) {
            return s == null ? null : new SupplierRef(s.getId(), s.getCode(), s.getName());
        }
    }

    public record Line(Integer lineNo, String itemName, String unit, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal amount, BigDecimal receivedQty, BigDecimal invoicedQty) {
        static Line of(PurchaseOrderLineEntity l) {
            return new Line(l.getLineNo(), l.getItemName(), l.getUnit(), l.getQuantity(), l.getUnitPrice(),
                    l.getAmount(), l.getReceivedQty(), l.getInvoicedQty());
        }
    }

    public record Receipt(String grNumber, String receiver, LocalDateTime createdAt, List<ReceiptLine> lines) {
    }

    public record ReceiptLine(Integer lineNo, BigDecimal quantity) {
    }

    public static PurchaseOrderView of(PurchaseOrderEntity o, SupplierEntity supplier,
                                       List<PurchaseOrderLineEntity> lines, List<Receipt> receipts) {
        return new PurchaseOrderView(o.getId(), o.getPoNumber(), o.getRequestId(), SupplierRef.of(supplier),
                o.getBuyer(), o.getTotalAmount(), o.getStatus(), o.getCreatedAt(),
                lines == null ? null : lines.stream().map(Line::of).toList(), receipts);
    }

    static Receipt receipt(GoodsReceiptEntity gr, List<ReceiptLine> lines) {
        return new Receipt(gr.getGrNumber(), gr.getReceiver(), gr.getCreatedAt(), lines);
    }
}
