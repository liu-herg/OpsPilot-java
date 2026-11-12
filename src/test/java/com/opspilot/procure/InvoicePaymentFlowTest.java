package com.opspilot.procure;

import com.opspilot.api.ConflictException;
import com.opspilot.approval.ApprovalService;
import com.opspilot.run.EventView;
import com.opspilot.run.RunService;
import com.opspilot.support.FakeGateway;
import com.opspilot.support.ItConfig;
import com.opspilot.support.MySqlProps;
import com.opspilot.support.P4Config;
import com.opspilot.support.ProcureFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采购到付款全链路（真实 MySQL）：收货 → 登记发票（三单匹配）→ 付款单 → 执行引擎 → 状态回写。
 * 付款依据用真实的 LedgerPayableSource 读内部单据，财务系统用 FakeGateway。
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = {ItConfig.class, P4Config.class})
class InvoicePaymentFlowTest {

    @Autowired
    private SupplierService suppliers;
    @Autowired
    private PurchaseRequestService requests;
    @Autowired
    private PurchaseOrderService orders;
    @Autowired
    private InvoiceService invoices;
    @Autowired
    private PaymentOrderService payments;
    @Autowired
    private RunService runService;
    @Autowired
    private FakeGateway gateway;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private JdbcTemplate jdbc;

    private ProcureFixtures fixtures;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        MySqlProps.register(registry);
    }

    @BeforeEach
    void setUp() {
        fixtures = new ProcureFixtures(suppliers, requests, jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanup();
    }

    @Test
    void matchedInvoiceIsPaidAndEverythingIsClosed() {
        PurchaseOrderView po = receivedOrder(new double[]{10, 380});   // 3800，低于审批阈值

        InvoiceView invoice = register(po, "INV-A", "3800", "494", "4294.00", line(1, "10", "380"));
        assertThat(invoice.status()).isEqualTo("PAYABLE");
        assertThat(invoice.payNumber()).matches("PAY-\\d{8}-\\d{4}");
        PaymentOrderView pending = paymentOf(invoice);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.amount()).isEqualByComparingTo("4294.00");     // 价税合计 = 3800 + 13% 税

        PaymentOrderView paid = payments.pay(pending.id());

        // 同步执行器下 pay() 返回时已经付完
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.financePaymentId()).isNotBlank();
        assertThat(gateway.postCount(po.poNumber())).isEqualTo(1);
        assertThat(invoices.get(invoice.id()).status()).isEqualTo("PAID");
        assertThat(orders.get(po.id()).status()).isEqualTo("CLOSED");
        assertThat(eventTypes(paid.runId())).contains("payment.submitted", "run.completed");

        // 已付款的付款单不能再发起
        assertThatThrownBy(() -> payments.pay(pending.id())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("PAID");
    }

    @Test
    void quickEntryFindsThePendingPaymentOrder() {
        PurchaseOrderView po = receivedOrder(new double[]{2, 1000});
        InvoiceView invoice = register(po, "INV-Q", "2000", "0", "2000", line(1, "2", "1000"));

        var created = runService.createTask("帮我处理 " + po.poNumber() + " 的付款");
        runService.start(created.runId());

        assertThat(paymentOf(invoice).status()).isEqualTo("PAID");
        // 再说一次：已经没有待付款的付款单了，业务上直接拦住
        assertThatThrownBy(() -> runService.createTask("帮我处理 " + po.poNumber() + " 的付款"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("没有待付款");
    }

    @Test
    void priceExceptionCanBeAcceptedThenPaid() {
        PurchaseOrderView po = receivedOrder(new double[]{1, 7600});
        InvoiceView invoice = register(po, "INV-P", "7500", "0", "7500", line(1, "1", "7500"));

        assertThat(invoice.status()).isEqualTo("EXCEPTION");
        assertThat(invoice.payNumber()).isNull();
        assertThat(invoice.exceptions()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("PRICE_MISMATCH");
            assertThat(e.detail()).containsEntry("deviation", "1.32%");
        });
        // 差异未处理前不占用开票数量
        assertThat(orders.get(po.id()).lines().get(0).invoicedQty()).isEqualByComparingTo("0");

        InvoiceView accepted = invoices.acceptException(invoice.exceptions().get(0).id(), "财务-陈静");
        assertThat(accepted.status()).isEqualTo("PAYABLE");
        assertThat(payments.pay(paymentOf(accepted).id()).status()).isEqualTo("PAID");
    }

    @Test
    void quantityExceptionNeedsReceiptAndRematch() {
        PurchaseOrderView po = fixtures.order(new double[]{10, 100});
        orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库", List.of(new ProcureCommands.ReceiptLine(1, bd("6")))));

        // 只到了 6 件，发票开了 10 件
        InvoiceView invoice = register(po, "INV-Q2", "1000", "0", "1000", line(1, "10", "100"));
        assertThat(invoice.status()).isEqualTo("EXCEPTION");
        Long qtyException = invoice.exceptions().get(0).id();
        assertThatThrownBy(() -> invoices.acceptException(qtyException, "财务"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("补收货");

        // 剩下 4 件到货后重新匹配，通过
        orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库", List.of(new ProcureCommands.ReceiptLine(1, bd("4")))));
        InvoiceView rematched = invoices.rematch(invoice.id(), "财务");
        assertThat(rematched.status()).isEqualTo("PAYABLE");
        assertThat(rematched.exceptions()).extracting(InvoiceView.Mismatch::status).containsExactly("RESOLVED");
    }

    @Test
    void rejectedExceptionVoidsTheInvoice() {
        PurchaseOrderView po = receivedOrder(new double[]{1, 7600});
        InvoiceView invoice = register(po, "INV-R", "7500", "0", "7500", line(1, "1", "7500"));

        InvoiceView rejected = invoices.rejectException(invoice.exceptions().get(0).id(), "财务");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.payNumber()).isNull();
    }

    @Test
    void duplicateInvoiceAndWrongSupplierAreRefused() {
        PurchaseOrderView po = receivedOrder(new double[]{4, 100});
        register(po, "INV-D", "200", "0", "200", line(1, "2", "100"));

        assertThatThrownBy(() -> register(po, "INV-D", "200", "0", "200", line(1, "2", "100")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已经登记过");

        SupplierView other = fixtures.supplier();
        assertThatThrownBy(() -> invoices.register(new ProcureCommands.RegisterInvoice("INV-X", other.id(),
                po.poNumber(), LocalDate.now(), bd("200"), bd("0"), bd("200"), "财务", List.of(line(1, "2", "100")))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("供应商");
    }

    @Test
    void frozenSupplierPaymentFailsAndCanBeRetriedAfterUnfreeze() {
        PurchaseOrderView po = receivedOrder(new double[]{1, 900});
        InvoiceView invoice = register(po, "INV-F", "900", "0", "900", line(1, "1", "900"));
        suppliers.freeze(po.supplier().id());

        PaymentOrderView failed = payments.pay(paymentOf(invoice).id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(runService.get(failed.runId()).errorType()).isEqualTo("BUSINESS_VALIDATION_FAILED");
        assertThat(gateway.postCount(po.poNumber())).isZero();

        suppliers.unfreeze(po.supplier().id());
        PaymentOrderView retried = payments.retry(failed.id());
        assertThat(retried.status()).isEqualTo("PAID");
        assertThat(retried.runId()).isNotEqualTo(failed.runId());
        assertThat(gateway.postCount(po.poNumber())).isEqualTo(1);
    }

    @Test
    void largePaymentWaitsForApprovalAndRejectionIsSynced() {
        PurchaseOrderView po = receivedOrder(new double[]{1, 12800});
        InvoiceView invoice = register(po, "INV-L", "12800", "0", "12800", line(1, "1", "12800"));

        PaymentOrderView executing = payments.pay(paymentOf(invoice).id());
        assertThat(executing.status()).isEqualTo("EXECUTING");
        assertThat(runService.get(executing.runId()).status()).isEqualTo("WAITING_APPROVAL");

        // 审批单 id 由运行 id 决定（见 RunExecutor.requestApproval）；驳回后付款单同步为 REJECTED
        String approvalId = "apr-" + executing.runId().substring(executing.runId().indexOf('-') + 1);
        approvals.decide(approvalId, "reject", "财务总监");
        assertThat(payments.get(executing.id()).status()).isEqualTo("REJECTED");
        assertThat(gateway.postCount(po.poNumber())).isZero();
    }

    @Test
    void samePaymentOrderIsStartedOnlyOnce() throws Exception {
        PurchaseOrderView po = receivedOrder(new double[]{1, 500});
        Long paymentId = paymentOf(register(po, "INV-C", "500", "0", "500", line(1, "1", "500"))).id();
        gateway.setSubmitDelayMs(200);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        payments.pay(paymentId);
                        ok.incrementAndGet();
                    } catch (ConflictException e) {
                        conflict.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            gateway.setSubmitDelayMs(0);
            pool.shutdownNow();
        }
        System.out.printf("[并发发起付款] 5 个线程同时发起同一张付款单：%d 个成功、%d 个冲突，运行 %d 个，POST %d 次%n",
                ok.get(), conflict.get(), runsOf(po.poNumber()), gateway.postCount(po.poNumber()));
        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(4);
        assertThat(runsOf(po.poNumber())).isEqualTo(1);
        assertThat(gateway.postCount(po.poNumber())).isEqualTo(1);
    }

    /**
     * 订单已收货 10 件，5 张发票同时各开 4 件。
     * 对照组："先查已开票数量、Java 里判断没超、再加上去"：几张发票查到的是同一个旧值，同一批货被开了好几次票。
     */
    @Test
    void concurrentInvoicesCannotBillTheSameReceivedQuantityTwice() throws Exception {
        int threads = 5;
        BigDecimal each = bd("4");

        PurchaseOrderView naiveOrder = receivedOrder(new double[]{10, 100});
        Long naiveLine = jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE po_id = ?", Long.class, naiveOrder.id());
        AtomicInteger naiveBilled = new AtomicInteger();
        runConcurrently(threads, () -> {
            java.util.Map<String, Object> row = jdbc.queryForMap(
                    "SELECT received_qty, invoiced_qty FROM purchase_order_line WHERE id = ?", naiveLine);
            BigDecimal available = ((BigDecimal) row.get("received_qty")).subtract((BigDecimal) row.get("invoiced_qty"));
            sleep(5);
            if (each.compareTo(available) <= 0) {
                jdbc.update("UPDATE purchase_order_line SET invoiced_qty = invoiced_qty + ? WHERE id = ?", each, naiveLine);
                naiveBilled.incrementAndGet();
            }
        });
        BigDecimal naiveInvoiced = jdbc.queryForObject("SELECT invoiced_qty FROM purchase_order_line WHERE id = ?",
                BigDecimal.class, naiveLine);

        PurchaseOrderView po = receivedOrder(new double[]{10, 100});
        AtomicInteger seq = new AtomicInteger();
        java.util.List<InvoiceView> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        runConcurrently(threads, () -> results.add(
                register(po, "INV-CC" + seq.incrementAndGet(), "400", "0", "400", line(1, "4", "100"))));
        long payable = results.stream().filter(i -> "PAYABLE".equals(i.status())).count();
        long exception = results.stream().filter(i -> "EXCEPTION".equals(i.status())).count();
        BigDecimal invoiced = orders.get(po.id()).lines().get(0).invoicedQty();

        System.out.printf("[并发开票] 已收货 10 件，5 张发票同时各开 4 件。先查后改：%d 张通过，已开票 %s 件（超开 %s 件）；"
                        + "锁订单 + 条件递增：%d 张通过、%d 张进差异单，已开票 %s 件%n",
                naiveBilled.get(), naiveInvoiced.stripTrailingZeros().toPlainString(),
                naiveInvoiced.subtract(bd("10")).max(BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
                payable, exception, invoiced.stripTrailingZeros().toPlainString());
        assertThat(naiveInvoiced).isGreaterThan(bd("10"));
        assertThat(payable).isEqualTo(2);
        assertThat(exception).isEqualTo(3);
        assertThat(invoiced).isEqualByComparingTo("8");
    }

    // ---------------------------------------------------------------- 辅助

    private static void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PurchaseOrderView receivedOrder(double[]... lines) {
        PurchaseOrderView po = fixtures.order(lines);
        List<ProcureCommands.ReceiptLine> all = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            all.add(new ProcureCommands.ReceiptLine(i + 1, BigDecimal.valueOf(lines[i][0])));
        }
        return orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库", all)).order();
    }

    private InvoiceView register(PurchaseOrderView po, String number, String amount, String tax, String total,
                                 ProcureCommands.InvoiceLine... lines) {
        return invoices.register(new ProcureCommands.RegisterInvoice(number + "-" + po.id(), po.supplier().id(),
                po.poNumber(), LocalDate.now(), bd(amount), bd(tax), bd(total), "财务-陈静", List.of(lines)));
    }

    private PaymentOrderView paymentOf(InvoiceView invoice) {
        Long id = jdbc.queryForObject("SELECT id FROM payment_order WHERE invoice_id = ?", Long.class, invoice.id());
        return payments.get(id);
    }

    private int runsOf(String po) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE po_number = ?", Integer.class, po);
    }

    private List<String> eventTypes(String runId) {
        return runService.events(runId, 0).stream().map(EventView::eventType).toList();
    }

    private static ProcureCommands.InvoiceLine line(int lineNo, String qty, String price) {
        return new ProcureCommands.InvoiceLine(lineNo, bd(qty), bd(price));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
