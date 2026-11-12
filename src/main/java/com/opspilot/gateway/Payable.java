package com.opspilot.gateway;

import java.math.BigDecimal;

/**
 * 付款依据：这一笔要付给谁、付多少。P4 起来自系统内部的付款单（P1~P3 来自外部采购系统）。
 *
 * @param supplierActive 供应商是否正常（冻结的供应商不能付款）
 */
public record Payable(String poNumber, String invoiceNumber, String supplier, BigDecimal amount, String currency,
                      boolean supplierActive) {
}
