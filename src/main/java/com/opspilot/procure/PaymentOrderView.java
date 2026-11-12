package com.opspilot.procure;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 付款单。run_id 是最近一次执行它的运行，可以用 /runs/{id}/events 查看付款时间线。 */
public record PaymentOrderView(Long id, String payNumber, String poNumber, String invoiceNumber,
                               PurchaseOrderView.SupplierRef supplier, BigDecimal amount, String status,
                               String runId, String financePaymentId, LocalDateTime createdAt) {
}
