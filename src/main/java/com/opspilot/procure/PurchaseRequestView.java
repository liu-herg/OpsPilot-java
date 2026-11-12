package com.opspilot.procure;

import com.opspilot.store.PurchaseRequestEntity;
import com.opspilot.store.PurchaseRequestLineEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 采购申请，含明细；已转订单时带上订单号。 */
public record PurchaseRequestView(Long id, String requestNo, String department, String requester, String title,
                                  BigDecimal totalAmount, String status, String decidedBy, LocalDateTime decidedAt,
                                  LocalDateTime createdAt, String poNumber, List<Line> lines) {

    public record Line(Integer lineNo, String itemName, String spec, BigDecimal quantity, String unit,
                       BigDecimal estUnitPrice, BigDecimal amount) {

        static Line of(PurchaseRequestLineEntity l) {
            return new Line(l.getLineNo(), l.getItemName(), l.getSpec(), l.getQuantity(), l.getUnit(),
                    l.getEstUnitPrice(), l.getAmount());
        }
    }

    public static PurchaseRequestView of(PurchaseRequestEntity r, List<PurchaseRequestLineEntity> lines,
                                         String poNumber) {
        return new PurchaseRequestView(r.getId(), r.getRequestNo(), r.getDepartment(), r.getRequester(),
                r.getTitle(), r.getTotalAmount(), r.getStatus(), r.getDecidedBy(), r.getDecidedAt(),
                r.getCreatedAt(), poNumber, lines == null ? null : lines.stream().map(Line::of).toList());
    }
}
