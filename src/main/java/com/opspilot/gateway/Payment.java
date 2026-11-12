package com.opspilot.gateway;

import java.math.BigDecimal;

/** 财务系统里的付款申请。 */
public record Payment(String id, String status, String poNumber, String invoiceId, String supplier,
                      BigDecimal amount, String costCenter, String reason) {
}
