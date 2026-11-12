package com.opspilot.procure;

import com.opspilot.store.SupplierMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 演示数据（opspilot.demo-data=true 时，库里还没有供应商才写入）。四个场景沿用之前的单号，
 * 全部通过正常的业务接口生成：申请 → 审批 → 转订单 → 收货 → 登记发票（三单匹配）。
 *
 * <pre>
 * PO-10001  ABC Technology  12800  发票 INV-8921 匹配通过   → 大额，走审批
 * PO-10002  Nova Office      7600  发票 INV-8922 匹配通过   → 直接付款
 * PO-10003  Cloud Harbor     7600  发票 INV-8923 单价 7500  → 单价差异 1.32%，进差异单
 * PO-10004  Lumen Supplies   5200  发票 INV-8924 匹配通过   → 付款后崩溃演示
 * </pre>
 * 在所有 Bean 初始化完、Web 服务启动之前写入：健康检查变成 UP 时演示数据一定已经在了。
 */
@Component
@ConditionalOnProperty(name = "opspilot.demo-data", havingValue = "true")
public class DemoDataInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final SupplierMapper supplierMapper;
    private final SupplierService suppliers;
    private final PurchaseRequestService requests;
    private final PurchaseOrderService orders;
    private final InvoiceService invoices;

    public DemoDataInitializer(SupplierMapper supplierMapper, SupplierService suppliers,
                               PurchaseRequestService requests, PurchaseOrderService orders, InvoiceService invoices) {
        this.supplierMapper = supplierMapper;
        this.suppliers = suppliers;
        this.requests = requests;
        this.orders = orders;
        this.invoices = invoices;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (supplierMapper.selectCount(null) > 0) {
            return;
        }
        try {
            seedCase("PO-10001", "SUP-ABC", "ABC Technology", "91310000ABC0001", "服务器内存扩容", 12800, "INV-8921", 12800);
            seedCase("PO-10002", "SUP-NOVA", "Nova Office", "91310000NOVA002", "办公家具", 7600, "INV-8922", 7600);
            seedCase("PO-10003", "SUP-CLOUD", "Cloud Harbor", "91310000CLD0003", "云存储年费", 7600, "INV-8923", 7500);
            seedCase("PO-10004", "SUP-LUMEN", "Lumen Supplies", "91310000LUM0004", "机房照明", 5200, "INV-8924", 5200);
            log.info("已写入演示数据：4 家供应商、4 张采购订单（PO-10001 ~ PO-10004）及发票");
        } catch (RuntimeException e) {
            // 多个实例同时启动时可能撞上唯一索引，另一个实例已经写好了
            log.warn("写入演示数据失败（可能已由其他实例写入）: {}", e.getMessage());
        }
    }

    private void seedCase(String poNumber, String code, String name, String taxNo, String item,
                          long orderPrice, String invoiceNumber, long invoicePrice) {
        SupplierView supplier = suppliers.create(new ProcureCommands.CreateSupplier(
                code, name, taxNo, "招商银行上海分行", "6214" + taxNo.substring(taxNo.length() - 8)));
        BigDecimal price = BigDecimal.valueOf(orderPrice);
        PurchaseRequestView request = requests.create(new ProcureCommands.CreateRequest("IT 运维部", "张敏", item,
                List.of(new ProcureCommands.RequestLine(item, null, BigDecimal.ONE, "批", price))));
        requests.submit(request.id());
        requests.approve(request.id(), "部门经理-李响");
        PurchaseOrderView po = requests.toOrder(request.id(),
                new ProcureCommands.CreateOrder(supplier.id(), "采购-王芳", null), poNumber);
        orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库-李强",
                List.of(new ProcureCommands.ReceiptLine(1, BigDecimal.ONE))));
        BigDecimal invoiceAmount = BigDecimal.valueOf(invoicePrice);
        invoices.register(new ProcureCommands.RegisterInvoice(invoiceNumber, supplier.id(), poNumber,
                LocalDate.now(), invoiceAmount, BigDecimal.ZERO, invoiceAmount, "财务-陈静",
                List.of(new ProcureCommands.InvoiceLine(1, BigDecimal.ONE, invoiceAmount))));
    }
}
