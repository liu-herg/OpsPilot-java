package com.opspilot.gateway;

import java.util.Optional;

/**
 * 外部财务系统（付款）。P4 起采购单和发票是系统内部数据（见 {@link PayableSource}），
 * 对外只剩真正有副作用的付款。现在的实现走 HTTP JSON 接口。
 */
public interface BusinessSystemGateway {

    /** 提交付款，有副作用。 */
    SubmitResult submitPayment(PaymentRequest request) throws SubmitException;

    /** 按业务键（PO + 发票）查询付款，用于提交后核实和故障后对账。 */
    Optional<Payment> findPayment(String poNumber, String invoiceId);
}
