package com.opspilot.procure;

import com.opspilot.api.ConflictException;
import com.opspilot.support.ItConfig;
import com.opspilot.support.MySqlProps;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 采购的并发问题：并发收货超收、并发转订单、并发取号。 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class ProcureConcurrencyTest {

    @Autowired
    private SupplierService suppliers;
    @Autowired
    private PurchaseRequestService requests;
    @Autowired
    private PurchaseOrderService orders;
    @Autowired
    private BizNumberService numbers;
    @Autowired
    private TransactionTemplate tx;
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
        jdbc.update("DELETE FROM biz_sequence WHERE name = 'ITSEQ'");
    }

    /**
     * 订单一行订了 20 件，10 个仓管同时各收 3 件。
     * 对照组："先查已收多少、Java 里判断没超、再加上去"，几个线程查到的是同一个旧值，都以为没超。
     */
    @Test
    void concurrentReceiptsNeverExceedOrderedQuantity() throws Exception {
        int threads = 10;
        BigDecimal each = new BigDecimal("3");

        PurchaseOrderView naiveOrder = fixtures.order(new double[]{20, 100});
        Long naiveLineId = lineId(naiveOrder.id());
        AtomicInteger naiveAccepted = new AtomicInteger();
        runConcurrently(threads, () -> {
            BigDecimal received = jdbc.queryForObject(
                    "SELECT received_qty FROM purchase_order_line WHERE id = ?", BigDecimal.class, naiveLineId);
            Thread.sleep(5);   // 查完到写入之间的业务处理
            if (received.add(each).compareTo(new BigDecimal("20")) <= 0) {
                jdbc.update("UPDATE purchase_order_line SET received_qty = received_qty + ? WHERE id = ?", each, naiveLineId);
                naiveAccepted.incrementAndGet();
            }
            return null;
        });
        BigDecimal naiveReceived = receivedQty(naiveLineId);

        PurchaseOrderView po = fixtures.order(new double[]{20, 100});
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrently(threads, () -> {
            try {
                orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓管",
                        List.of(new ProcureCommands.ReceiptLine(1, each))));
                accepted.incrementAndGet();
            } catch (ConflictException e) {
                rejected.incrementAndGet();
            }
            return null;
        });
        PurchaseOrderView after = orders.get(po.id());

        System.out.printf("[并发收货] 订 20 件，10 个线程各收 3 件。先查后改：%d 次成功，实收 %s 件（超收 %s 件）；"
                        + "锁订单 + 条件递增：%d 次成功、%d 次拒绝，实收 %s 件，收货单 %d 张%n",
                naiveAccepted.get(), naiveReceived.stripTrailingZeros().toPlainString(),
                naiveReceived.subtract(new BigDecimal("20")).max(BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
                accepted.get(), rejected.get(),
                after.lines().get(0).receivedQty().stripTrailingZeros().toPlainString(), after.receipts().size());

        assertThat(naiveReceived).isGreaterThan(new BigDecimal("20"));
        assertThat(accepted.get()).isEqualTo(6);
        assertThat(rejected.get()).isEqualTo(4);
        assertThat(after.lines().get(0).receivedQty()).isEqualByComparingTo("18");
        assertThat(after.receipts()).hasSize(6);
        assertThat(after.status()).isEqualTo("PARTIALLY_RECEIVED");
    }

    @Test
    void sameRequestIsOrderedOnlyOnce() throws Exception {
        SupplierView supplier = fixtures.supplier();
        PurchaseRequestView r = fixtures.approvedRequest("张敏", new double[]{5, 100});
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        runConcurrently(5, () -> {
            try {
                requests.toOrder(r.id(), new ProcureCommands.CreateOrder(supplier.id(), "采购", null));
                ok.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
            return null;
        });

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE request_id = ?", Integer.class, r.id()))
                .isEqualTo(1);
    }

    @Test
    void concurrentNumbersAreUnique() throws Exception {
        int threads = 20;
        List<String> issued = java.util.Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, () -> {
            issued.add(tx.execute(s -> numbers.next("ITSEQ")));
            return null;
        });
        System.out.printf("[业务单号] %d 个线程同时取号：%d 个不重复%n", threads, new HashSet<>(issued).size());
        assertThat(new HashSet<>(issued)).hasSize(threads);
    }

    private Long lineId(Long poId) {
        return jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE po_id = ? AND line_no = 1", Long.class, poId);
    }

    private BigDecimal receivedQty(Long lineId) {
        return jdbc.queryForObject("SELECT received_qty FROM purchase_order_line WHERE id = ?", BigDecimal.class, lineId);
    }

    private static void runConcurrently(int threads, Callable<Void> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
