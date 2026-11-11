package com.opspilot.gateway;

public record PaymentRequest(String poNumber, String invoiceId, String supplier, long amount,
                             String costCenter, String reason) {
}
