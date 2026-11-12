package com.opspilot.run;

/** 一次运行要付的是哪一笔：采购单号，P4 起还有发票号和付款单（P1~P3 的运行这两项为空）。 */
public record RunTarget(String poNumber, String invoiceNumber, Long paymentOrderId) {
}
