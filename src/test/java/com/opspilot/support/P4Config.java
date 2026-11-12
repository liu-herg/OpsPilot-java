package com.opspilot.support;

import com.opspilot.procure.InvoiceService;
import com.opspilot.procure.LedgerPayableSource;
import com.opspilot.procure.PaymentOrderRunTargets;
import com.opspilot.procure.PaymentOrderService;
import com.opspilot.procure.PaymentOrderSync;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * P4 端到端测试：在 {@link ItConfig} 之上加发票、付款单，以及真实的付款依据（{@link LedgerPayableSource}，@Primary，
 * 读内部单据）。FakeGateway 在这里只当外部财务系统用。
 */
@Configuration
@Import({InvoiceService.class, PaymentOrderService.class, PaymentOrderRunTargets.class, PaymentOrderSync.class,
        LedgerPayableSource.class})
public class P4Config {
}
