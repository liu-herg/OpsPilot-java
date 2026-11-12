package com.opspilot.procure;

import com.opspilot.store.SupplierEntity;

import java.time.LocalDateTime;

/** 供应商。银行账号只显示后 4 位。 */
public record SupplierView(Long id, String code, String name, String taxNo, String bankName, String bankAccount,
                           String status, LocalDateTime createdAt) {

    public static SupplierView of(SupplierEntity s) {
        return new SupplierView(s.getId(), s.getCode(), s.getName(), s.getTaxNo(), s.getBankName(),
                mask(s.getBankAccount()), s.getStatus(), s.getCreatedAt());
    }

    static String mask(String account) {
        if (account == null || account.length() <= 4) {
            return account;
        }
        return "****" + account.substring(account.length() - 4);
    }
}
