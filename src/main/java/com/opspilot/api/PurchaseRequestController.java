package com.opspilot.api;

import com.opspilot.procure.PageResult;
import com.opspilot.procure.ProcureCommands;
import com.opspilot.procure.PurchaseOrderView;
import com.opspilot.procure.PurchaseRequestService;
import com.opspilot.procure.PurchaseRequestView;
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
@RequestMapping("/purchase-requests")
public class PurchaseRequestController {

    private final PurchaseRequestService requests;

    public PurchaseRequestController(PurchaseRequestService requests) {
        this.requests = requests;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseRequestView create(@Valid @RequestBody ProcureCommands.CreateRequest body) {
        return requests.create(body);
    }

    @GetMapping
    public PageResult<PurchaseRequestView> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String department,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return requests.list(status, department, page, size);
    }

    @GetMapping("/{id}")
    public PurchaseRequestView get(@PathVariable Long id) {
        return requests.get(id);
    }

    @PostMapping("/{id}/submit")
    public PurchaseRequestView submit(@PathVariable Long id) {
        return requests.submit(id);
    }

    @PostMapping("/{id}/approve")
    public PurchaseRequestView approve(@PathVariable Long id, @Valid @RequestBody ProcureCommands.Decide body) {
        return requests.approve(id, body.actor());
    }

    @PostMapping("/{id}/reject")
    public PurchaseRequestView reject(@PathVariable Long id, @Valid @RequestBody ProcureCommands.Decide body) {
        return requests.reject(id, body.actor());
    }

    @PostMapping("/{id}/cancel")
    public PurchaseRequestView cancel(@PathVariable Long id) {
        return requests.cancel(id);
    }

    /** 转采购订单。body: {"supplier_id": 1, "buyer": "采购-王芳", "prices": [{"line_no": 1, "unit_price": 95}]} */
    @PostMapping("/{id}/order")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderView order(@PathVariable Long id, @Valid @RequestBody ProcureCommands.CreateOrder body) {
        return requests.toOrder(id, body);
    }
}
