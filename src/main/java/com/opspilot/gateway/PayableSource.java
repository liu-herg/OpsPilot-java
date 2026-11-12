package com.opspilot.gateway;

import java.util.Optional;

/**
 * 执行付款前要读的两份数据：付款依据（付款单 / 采购单）和发票。执行器拿两份数据互相复核后才提交付款。
 *
 * <p>生产环境的实现读系统内部的单据（{@code LedgerPayableSource}）；测试里由 FakeGateway 提供可控的数据和故障。
 * invoiceNumber 为空时表示"这张采购单唯一的那张发票"（P1~P3 的运行没有发票号）。
 */
public interface PayableSource {

    /** @return 找不到时为空；数据源暂时不可用时抛 {@link UpstreamUnavailableException} */
    Optional<Payable> findPayable(String poNumber, String invoiceNumber);

    Optional<Invoice> findInvoice(String poNumber, String invoiceNumber);
}
