package com.opspilot.api;

import com.opspilot.procure.PageResult;
import com.opspilot.procure.PaymentOrderService;
import com.opspilot.procure.PaymentOrderView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment-orders")
public class PaymentOrderController {

    private final PaymentOrderService payments;

    public PaymentOrderController(PaymentOrderService payments) {
        this.payments = payments;
    }

    @GetMapping
    public PageResult<PaymentOrderView> list(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return payments.list(status, page, size);
    }

    @GetMapping("/{id}")
    public PaymentOrderView get(@PathVariable Long id) {
        return payments.get(id);
    }

    /** 发起付款：创建运行并启动，执行过程看 /runs/{run_id}/events。 */
    @PostMapping("/{id}/pay")
    public PaymentOrderView pay(@PathVariable Long id) {
        return payments.pay(id);
    }

    /** 失败后重新发起。 */
    @PostMapping("/{id}/retry")
    public PaymentOrderView retry(@PathVariable Long id) {
        return payments.retry(id);
    }
}
