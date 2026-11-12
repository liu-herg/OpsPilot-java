package com.opspilot.support;

import com.opspilot.procure.ProcureCommands;
import com.opspilot.procure.PurchaseOrderView;
import com.opspilot.procure.PurchaseRequestService;
import com.opspilot.procure.PurchaseRequestView;
import com.opspilot.procure.SupplierService;
import com.opspilot.procure.SupplierView;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** 采购测试数据：造供应商、申请、订单；测试结束按记下的 id 清理。 */
public class ProcureFixtures {

    private final SupplierService suppliers;
    private final PurchaseRequestService requests;
    private final JdbcTemplate jdbc;
    private final List<Long> supplierIds = new CopyOnWriteArrayList<>();

    public ProcureFixtures(SupplierService suppliers, PurchaseRequestService requests, JdbcTemplate jdbc) {
        this.suppliers = suppliers;
        this.requests = requests;
        this.jdbc = jdbc;
    }

    public SupplierView supplier() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        SupplierView s = suppliers.create(new ProcureCommands.CreateSupplier(
                "IT-" + tag, "测试供应商 " + tag, "IT" + tag.toUpperCase(), "测试银行", "6222000011112222"));
        supplierIds.add(s.id());
        return s;
    }

    /** 已批准、待转订单的申请。lines：每行 {数量, 单价}。 */
    public PurchaseRequestView approvedRequest(String requester, double[]... lines) {
        List<ProcureCommands.RequestLine> in = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            in.add(new ProcureCommands.RequestLine("物料 " + (i + 1), null,
                    BigDecimal.valueOf(lines[i][0]), "件", BigDecimal.valueOf(lines[i][1])));
        }
        PurchaseRequestView r = requests.create(new ProcureCommands.CreateRequest("IT-测试部", requester, "测试采购", in));
        requests.submit(r.id());
        return requests.approve(r.id(), "部门经理");
    }

    /** 一张已下达的订单。 */
    public PurchaseOrderView order(double[]... lines) {
        SupplierView s = supplier();
        PurchaseRequestView r = approvedRequest("申请人-" + UUID.randomUUID().toString().substring(0, 4), lines);
        return requests.toOrder(r.id(), new ProcureCommands.CreateOrder(s.id(), "采购-测试", null));
    }

    public void cleanup() {
        for (Long sid : supplierIds) {
            List<String> pos = jdbc.queryForList("SELECT po_number FROM purchase_order WHERE supplier_id = ?", String.class, sid);
            TestData.cleanup(jdbc, pos);
            jdbc.update("DELETE FROM payment_order WHERE supplier_id = ?", sid);
            jdbc.update("DELETE e FROM match_exception e JOIN invoice i ON i.id = e.invoice_id WHERE i.supplier_id = ?", sid);
            jdbc.update("DELETE l FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id WHERE i.supplier_id = ?", sid);
            jdbc.update("DELETE FROM invoice WHERE supplier_id = ?", sid);
            jdbc.update("DELETE gl FROM goods_receipt_line gl JOIN goods_receipt g ON g.id = gl.gr_id "
                    + "JOIN purchase_order o ON o.id = g.po_id WHERE o.supplier_id = ?", sid);
            jdbc.update("DELETE g FROM goods_receipt g JOIN purchase_order o ON o.id = g.po_id WHERE o.supplier_id = ?", sid);
            jdbc.update("DELETE l FROM purchase_order_line l JOIN purchase_order o ON o.id = l.po_id WHERE o.supplier_id = ?", sid);
            jdbc.update("DELETE FROM purchase_order WHERE supplier_id = ?", sid);
            jdbc.update("DELETE FROM supplier WHERE id = ?", sid);
        }
        supplierIds.clear();
        jdbc.update("DELETE l FROM purchase_request_line l JOIN purchase_request r ON r.id = l.request_id "
                + "WHERE r.department = 'IT-测试部'");
        jdbc.update("DELETE FROM purchase_request WHERE department = 'IT-测试部'");
    }
}
