package com.opspilot.api;

import com.opspilot.procure.InvoiceService;
import com.opspilot.procure.InvoiceView;
import com.opspilot.procure.PageResult;
import com.opspilot.procure.ProcureCommands;
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

import java.util.List;
import java.util.Map;

@RestController
public class InvoiceController {

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    /** 登记发票，同步做三单匹配：返回 PAYABLE（已生成付款单）或 EXCEPTION（附差异）。 */
    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceView register(@Valid @RequestBody ProcureCommands.RegisterInvoice body) {
        return invoices.register(body);
    }

    @GetMapping("/invoices")
    public PageResult<InvoiceView> list(@RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return invoices.list(status, page, size);
    }

    @GetMapping("/invoices/{id}")
    public InvoiceView get(@PathVariable Long id) {
        return invoices.get(id);
    }

    /** 重新匹配（例如补收货之后）。body: {"actor": "财务-陈静"} */
    @PostMapping("/invoices/{id}/rematch")
    public InvoiceView rematch(@PathVariable Long id, @Valid @RequestBody ProcureCommands.Decide body) {
        return invoices.rematch(id, body.actor());
    }

    @GetMapping("/match-exceptions")
    public List<Map<String, Object>> exceptions(@RequestParam(required = false) String status) {
        return invoices.listExceptions(status);
    }

    @PostMapping("/match-exceptions/{id}/accept")
    public InvoiceView accept(@PathVariable Long id, @Valid @RequestBody ProcureCommands.Decide body) {
        return invoices.acceptException(id, body.actor());
    }

    @PostMapping("/match-exceptions/{id}/reject")
    public InvoiceView reject(@PathVariable Long id, @Valid @RequestBody ProcureCommands.Decide body) {
        return invoices.rejectException(id, body.actor());
    }
}
