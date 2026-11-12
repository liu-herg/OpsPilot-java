package com.opspilot.support;

import com.opspilot.gateway.BusinessSystemGateway;
import com.opspilot.gateway.Invoice;
import com.opspilot.gateway.Payable;
import com.opspilot.gateway.PayableSource;
import com.opspilot.gateway.Payment;
import com.opspilot.gateway.PaymentRequest;
import com.opspilot.gateway.SubmitException;
import com.opspilot.gateway.SubmitResult;
import com.opspilot.gateway.UpstreamUnavailableException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用业务系统：既是付款依据的来源（{@link PayableSource}），也是财务系统（{@link BusinessSystemGateway}）。
 * 注意它的财务系统故意<b>不做</b>幂等：每次 POST 都新建一笔付款。
 * 这样"只付了一笔"完全是控制面自己保证的，而不是靠对方去重。
 * P4 的端到端测试用真实的 LedgerPayableSource（@Primary）读内部单据，这里只当财务系统用。
 */
public class FakeGateway implements BusinessSystemGateway, PayableSource {

    public enum Failure {
        /** 连接失败，请求没到达 */
        NOT_SENT,
        /** 财务系统已经建好付款，但响应丢了 */
        RESPONSE_LOST,
        /** 财务系统拒绝（4xx） */
        REJECTED
    }

    private record Fixture(Payable order, Invoice invoice) {
    }

    private final Map<String, Fixture> fixtures = new ConcurrentHashMap<>();
    private final Map<String, List<Payment>> payments = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> posts = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<Failure>> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> readFailures = new ConcurrentHashMap<>();
    private volatile long submitDelayMs;

    /** 登记一笔采购单 + 发票，返回发票号。 */
    public String addCase(String po, String supplier, long orderAmount, long invoiceAmount) {
        String invoiceId = "INV-" + po.substring(3);
        fixtures.put(po, new Fixture(
                new Payable(po, invoiceId, supplier, BigDecimal.valueOf(orderAmount), "CNY", true),
                new Invoice(invoiceId, po, supplier, BigDecimal.valueOf(invoiceAmount), "Valid")));
        return invoiceId;
    }

    public void failNextSubmit(String po, Failure failure) {
        failures.computeIfAbsent(po, k -> new ConcurrentLinkedDeque<>()).addLast(failure);
    }

    /** 接下来 times 次查询采购单时，采购系统"不可用"。 */
    public void failReads(String po, int times) {
        readFailures.put(po, new AtomicInteger(times));
    }

    /** 拉长"请求处理中"的窗口，让并发更容易撞上。 */
    public void setSubmitDelayMs(long ms) {
        this.submitDelayMs = ms;
    }

    public int postCount(String po) {
        AtomicInteger n = posts.get(po);
        return n == null ? 0 : n.get();
    }

    public int paymentCount(String po) {
        List<Payment> list = payments.get(po);
        return list == null ? 0 : list.size();
    }

    @Override
    public Optional<Payable> findPayable(String poNumber, String invoiceNumber) {
        AtomicInteger failures = readFailures.get(poNumber);
        if (failures != null && failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new UpstreamUnavailableException("fake: 采购系统不可用", null);
        }
        return Optional.ofNullable(fixtures.get(poNumber)).map(Fixture::order);
    }

    @Override
    public Optional<Invoice> findInvoice(String poNumber, String invoiceNumber) {
        return Optional.ofNullable(fixtures.get(poNumber)).map(Fixture::invoice);
    }

    @Override
    public SubmitResult submitPayment(PaymentRequest request) {
        String po = request.poNumber();
        posts.computeIfAbsent(po, k -> new AtomicInteger()).incrementAndGet();
        sleep(submitDelayMs);
        Failure failure = nextFailure(po);
        if (failure == Failure.NOT_SENT) {
            throw new SubmitException(SubmitException.Kind.NOT_SENT, "fake: connection refused");
        }
        if (failure == Failure.REJECTED) {
            throw new SubmitException(SubmitException.Kind.REJECTED, "fake: HTTP 422");
        }
        Payment payment = new Payment("PAY-" + po.substring(3) + "-" + (paymentCount(po) + 1), "Submitted",
                po, request.invoiceId(), request.supplier(), request.amount(), request.costCenter(), request.reason());
        payments.computeIfAbsent(po, k -> new CopyOnWriteArrayList<>()).add(payment);
        if (failure == Failure.RESPONSE_LOST) {
            throw new SubmitException(SubmitException.Kind.OUTCOME_UNKNOWN, "fake: read timeout");
        }
        return new SubmitResult(payment, true);
    }

    @Override
    public Optional<Payment> findPayment(String poNumber, String invoiceId) {
        List<Payment> list = payments.get(poNumber);
        return list == null ? Optional.empty()
                : list.stream().filter(p -> p.invoiceId().equals(invoiceId)).findFirst();
    }

    private Failure nextFailure(String po) {
        ConcurrentLinkedDeque<Failure> queue = failures.get(po);
        return queue == null ? null : queue.pollFirst();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
