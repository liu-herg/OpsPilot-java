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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 采购主干（真实 MySQL）：供应商 → 申请 → 审批 → 转订单 → 分批收货。 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = ItConfig.class)
class ProcureFlowTest {

    @Autowired
    private SupplierService suppliers;
    @Autowired
    private PurchaseRequestService requests;
    @Autowired
    private PurchaseOrderService orders;
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
    void requestToOrderToReceiptsInBatches() {
        SupplierView supplier = fixtures.supplier();
        PurchaseRequestView draft = requests.create(new ProcureCommands.CreateRequest("IT-测试部", "张敏", "办公用品", List.of(
                new ProcureCommands.RequestLine("显示器", "27 寸", new BigDecimal("10"), "台", new BigDecimal("1200")),
                new ProcureCommands.RequestLine("键盘", null, new BigDecimal("20"), "个", new BigDecimal("99.5")))));

        assertThat(draft.requestNo()).matches("PR-\\d{8}-\\d{4}");
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.totalAmount()).isEqualByComparingTo("13990.00");   // 12000 + 1990

        requests.submit(draft.id());
        PurchaseRequestView approved = requests.approve(draft.id(), "部门经理-李响");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.decidedBy()).isEqualTo("部门经理-李响");

        // 转订单：显示器谈到 1150
        PurchaseOrderView po = requests.toOrder(draft.id(), new ProcureCommands.CreateOrder(supplier.id(), "采购-王芳",
                List.of(new ProcureCommands.PriceOverride(1, new BigDecimal("1150")))));
        assertThat(po.poNumber()).matches("PO-\\d{8}-\\d{4}");
        assertThat(po.status()).isEqualTo("ISSUED");
        assertThat(po.totalAmount()).isEqualByComparingTo("13490.00");     // 11500 + 1990
        assertThat(requests.get(draft.id()).poNumber()).isEqualTo(po.poNumber());
        assertThat(requests.get(draft.id()).status()).isEqualTo("ORDERED");

        // 第一批：显示器到 6 台
        PurchaseOrderService.ReceiptResult first = orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库-李强",
                List.of(new ProcureCommands.ReceiptLine(1, new BigDecimal("6")))));
        assertThat(first.grNumber()).matches("GR-\\d{8}-\\d{4}");
        assertThat(first.order().status()).isEqualTo("PARTIALLY_RECEIVED");

        // 第二批：剩下的全部到齐
        PurchaseOrderService.ReceiptResult second = orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库-李强",
                List.of(new ProcureCommands.ReceiptLine(1, new BigDecimal("4")),
                        new ProcureCommands.ReceiptLine(2, new BigDecimal("20")))));
        PurchaseOrderView received = second.order();
        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(received.lines()).extracting(PurchaseOrderView.Line::receivedQty)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10"), new BigDecimal("20"));
        assertThat(received.receipts()).hasSize(2);

        // 收齐的订单不能再收货，也不能取消
        assertThatThrownBy(() -> orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库-李强",
                List.of(new ProcureCommands.ReceiptLine(1, new BigDecimal("1"))))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orders.cancel(po.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void requesterCannotApproveOwnRequest() {
        PurchaseRequestView r = requests.create(new ProcureCommands.CreateRequest("IT-测试部", "张敏", "自批", List.of(
                new ProcureCommands.RequestLine("纸", null, BigDecimal.ONE, "箱", new BigDecimal("50")))));
        requests.submit(r.id());

        assertThatThrownBy(() -> requests.approve(r.id(), "张敏")).hasMessageContaining("不能审批自己");
        assertThat(requests.get(r.id()).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void illegalTransitionsAreRefused() {
        PurchaseRequestView r = requests.create(new ProcureCommands.CreateRequest("IT-测试部", "张敏", "状态", List.of(
                new ProcureCommands.RequestLine("纸", null, BigDecimal.ONE, "箱", new BigDecimal("50")))));

        // 草稿不能直接批准
        assertThatThrownBy(() -> requests.approve(r.id(), "经理")).isInstanceOf(ConflictException.class)
                .hasMessageContaining("DRAFT");
        requests.cancel(r.id());
        // 撤销后不能再提交
        assertThatThrownBy(() -> requests.submit(r.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void frozenSupplierCannotTakeOrdersAndRequestStaysApproved() {
        SupplierView supplier = fixtures.supplier();
        suppliers.freeze(supplier.id());
        PurchaseRequestView r = fixtures.approvedRequest("张敏", new double[]{5, 100});

        assertThatThrownBy(() -> requests.toOrder(r.id(), new ProcureCommands.CreateOrder(supplier.id(), "采购", null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("冻结");
        assertThat(requests.get(r.id()).status()).isEqualTo("APPROVED");

        // 解冻后可以下单；同一个申请不能转两次
        suppliers.unfreeze(supplier.id());
        requests.toOrder(r.id(), new ProcureCommands.CreateOrder(supplier.id(), "采购", null));
        assertThatThrownBy(() -> requests.toOrder(r.id(), new ProcureCommands.CreateOrder(supplier.id(), "采购", null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("ORDERED");
    }

    @Test
    void overReceiptIsRejectedAndNothingIsWritten() {
        PurchaseOrderView po = fixtures.order(new double[]{10, 100}, new double[]{5, 20});

        // 第 1 行正常、第 2 行超收：整张收货单回滚，第 1 行也不会记上
        assertThatThrownBy(() -> orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库", List.of(
                new ProcureCommands.ReceiptLine(1, new BigDecimal("3")),
                new ProcureCommands.ReceiptLine(2, new BigDecimal("6"))))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("第 2 行超收");

        PurchaseOrderView after = orders.get(po.id());
        assertThat(after.status()).isEqualTo("ISSUED");
        assertThat(after.receipts()).isEmpty();
        assertThat(after.lines().get(0).receivedQty()).isEqualByComparingTo("0");
    }

    @Test
    void unreceivedOrderCanBeCancelled() {
        PurchaseOrderView po = fixtures.order(new double[]{1, 100});
        assertThat(orders.cancel(po.id()).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> orders.receive(po.id(), new ProcureCommands.CreateReceipt("仓库",
                List.of(new ProcureCommands.ReceiptLine(1, BigDecimal.ONE))))).isInstanceOf(ConflictException.class);
    }

    @Test
    void duplicateSupplierIsRefused() {
        SupplierView s = fixtures.supplier();
        assertThatThrownBy(() -> suppliers.create(new ProcureCommands.CreateSupplier(
                s.code(), "另一家", "OTHER-TAX-" + s.id(), "银行", "123456789")))
                .isInstanceOf(ConflictException.class);
        // 冻结是幂等的
        suppliers.freeze(s.id());
        assertThat(suppliers.freeze(s.id()).status()).isEqualTo("FROZEN");
    }
}
