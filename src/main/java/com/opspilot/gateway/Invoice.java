package com.opspilot.gateway;

import java.math.BigDecimal;

/** 发票（amount 为价税合计，即应付金额）。 */
public record Invoice(String invoiceId, String poId, String supplier, BigDecimal amount, String status) {
}
