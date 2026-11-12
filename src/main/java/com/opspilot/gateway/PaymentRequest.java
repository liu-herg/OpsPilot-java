package com.opspilot.gateway;

import java.math.BigDecimal;

public record PaymentRequest(String poNumber, String invoiceId, String supplier, BigDecimal amount,
                             String costCenter, String reason) {
}
