package com.opspilot.gateway;

/** 财务系统里的付款申请。 */
public record Payment(String id, String status, String poNumber, String invoiceId, String supplier,
                      long amount, String costCenter, String reason) {
}
