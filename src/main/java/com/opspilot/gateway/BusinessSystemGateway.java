package com.opspilot.gateway;

import java.util.Optional;

/**
 * 访问三个业务系统（采购、发票、财务）的统一接口。
 * 现在的实现走 HTTP JSON 接口；P3 加入浏览器自动化执行器（Playwright）时实现同一个接口，控制面代码不用改。
 */
public interface BusinessSystemGateway {

    /** @return 采购单不存在时为空 */
    Optional<PurchaseOrder> findOrder(String poNumber);

    /** @return 该采购单没有发票时为空 */
    Optional<Invoice> findInvoiceByPo(String poNumber);

    /** 提交付款，有副作用。 */
    SubmitResult submitPayment(PaymentRequest request) throws SubmitException;

    /** 按业务键（PO + 发票）查询付款，用于提交后核实和故障后对账。 */
    Optional<Payment> findPayment(String poNumber, String invoiceId);
}
