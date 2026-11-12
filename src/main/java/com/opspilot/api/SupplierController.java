package com.opspilot.api;

import com.opspilot.procure.PageResult;
import com.opspilot.procure.ProcureCommands;
import com.opspilot.procure.SupplierService;
import com.opspilot.procure.SupplierView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/suppliers")
public class SupplierController {

    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierView create(@Valid @RequestBody ProcureCommands.CreateSupplier body) {
        return suppliers.create(body);
    }

    @GetMapping
    public PageResult<SupplierView> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return suppliers.list(status, keyword, page, size);
    }

    @GetMapping("/{id}")
    public SupplierView get(@PathVariable Long id) {
        return suppliers.get(id);
    }

    @PutMapping("/{id}")
    public SupplierView update(@PathVariable Long id, @Valid @RequestBody ProcureCommands.UpdateSupplier body) {
        return suppliers.update(id, body);
    }

    @PostMapping("/{id}/freeze")
    public SupplierView freeze(@PathVariable Long id) {
        return suppliers.freeze(id);
    }

    @PostMapping("/{id}/unfreeze")
    public SupplierView unfreeze(@PathVariable Long id) {
        return suppliers.unfreeze(id);
    }
}
