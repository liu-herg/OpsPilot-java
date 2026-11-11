package com.opspilot.gateway;

/** 采购系统返回的采购单。 */
public record PurchaseOrder(String id, String supplier, long amount, String currency, String status) {
}
