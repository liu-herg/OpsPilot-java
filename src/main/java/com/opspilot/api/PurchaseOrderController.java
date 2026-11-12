package com.opspilot.api;

import com.opspilot.procure.PageResult;
import com.opspilot.procure.ProcureCommands;
import com.opspilot.procure.PurchaseOrderService;
import com.opspilot.procure.PurchaseOrderView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService orders;

    public PurchaseOrderController(PurchaseOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public PageResult<PurchaseOrderView> list(@RequestParam(required = false) String status,
                                              @RequestParam(name = "supplier_id", required = false) Long supplierId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return orders.list(status, supplierId, page, size);
    }

    /** 按 id 或订单号查：/purchase-orders/12 或 /purchase-orders/PO-20261112-0001 */
    @GetMapping("/{idOrNumber}")
    public PurchaseOrderView get(@PathVariable String idOrNumber) {
        return idOrNumber.chars().allMatch(Character::isDigit)
                ? orders.get(Long.valueOf(idOrNumber))
                : orders.getByNumber(idOrNumber);
    }

    @PostMapping("/{id}/cancel")
    public PurchaseOrderView cancel(@PathVariable Long id) {
        return orders.cancel(id);
    }

    /** 收货。body: {"receiver": "仓库-李强", "lines": [{"line_no": 1, "quantity": 5}]} */
    @PostMapping("/{id}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderService.ReceiptResult receive(@PathVariable Long id,
                                                      @Valid @RequestBody ProcureCommands.CreateReceipt body) {
        return orders.receive(id, body);
    }
}
