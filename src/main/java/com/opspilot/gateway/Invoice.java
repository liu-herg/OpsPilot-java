package com.opspilot.gateway;

/** 发票系统返回的发票。 */
public record Invoice(String invoiceId, String poId, String supplier, long amount, String status) {
}
