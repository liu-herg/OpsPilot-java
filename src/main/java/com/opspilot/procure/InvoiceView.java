package com.opspilot.procure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 发票：明细、三单匹配差异、生成的付款单号。列表接口不带明细和差异（为 null）。 */
public record InvoiceView(Long id, String invoiceNumber, String poNumber, PurchaseOrderView.SupplierRef supplier,
                          LocalDate invoiceDate, BigDecimal amount, BigDecimal taxAmount, BigDecimal totalAmount,
                          String status, String registeredBy, LocalDateTime createdAt, String payNumber,
                          List<Line> lines, List<Mismatch> exceptions) {

    public record Line(Integer lineNo, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    /** 一条三单匹配差异。 */
    public record Mismatch(Long id, Integer lineNo, String type, Map<String, Object> detail, String status,
                            String resolver, LocalDateTime resolvedAt) {
    }
}
