package com.opspilot.procure;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThreeWayMatcherTest {

    private final ThreeWayMatcher matcher = new ThreeWayMatcher(new BigDecimal("0.01"));

    /** 订单第 1 行：单价 100，已收 10，已开票 4。 */
    private final Map<Integer, ThreeWayMatcher.OrderLine> order = Map.of(1,
            new ThreeWayMatcher.OrderLine(11L, 1, bd("100"), bd("10"), bd("4")));

    @Test
    void matchingInvoicePasses() {
        assertThat(match(line(6, "100"), "600", "78", "678")).isEmpty();
    }

    @Test
    void quantityBeyondReceivedMinusInvoicedIsFlagged() {
        // 已收 10、已开 4，只剩 6 可开
        List<ThreeWayMatcher.Mismatch> m = match(line(7, "100"), "700", "0", "700");
        assertThat(m).singleElement().satisfies(x -> {
            assertThat(x.type()).isEqualTo(ThreeWayMatcher.QTY_EXCEEDS_RECEIVED);
            assertThat(x.detail()).containsEntry("available_qty", "6");
        });
    }

    @Test
    void priceToleranceBoundary() {
        // 101 偏差正好 1%，允许；101.01 超过 1%
        assertThat(match(line(1, "101"), "101", "0", "101")).isEmpty();
        assertThat(match(line(1, "101.01"), "101.01", "0", "101.01"))
                .extracting(ThreeWayMatcher.Mismatch::type).containsExactly(ThreeWayMatcher.PRICE_MISMATCH);
        // 低于订单价同样算差异（PO-10003：7600 → 7500，偏差 1.32%）
        Map<Integer, ThreeWayMatcher.OrderLine> o = Map.of(1, new ThreeWayMatcher.OrderLine(1L, 1, bd("7600"), bd("1"), bd("0")));
        List<ThreeWayMatcher.Mismatch> m = matcher.match(o, List.of(line(1, "7500")), bd("7500"), bd("0"), bd("7500"));
        assertThat(m).singleElement().satisfies(x -> assertThat(x.detail()).containsEntry("deviation", "1.32%"));
    }

    @Test
    void headerTotalsMustAddUp() {
        // 明细合计 600，但发票头写 610；价税合计也对不上
        assertThat(match(line(6, "100"), "610", "78", "678"))
                .extracting(ThreeWayMatcher.Mismatch::type)
                .containsExactly(ThreeWayMatcher.TOTAL_MISMATCH, ThreeWayMatcher.TOTAL_MISMATCH);
        // 1 分以内的误差允许
        assertThat(match(line(6, "100"), "600.01", "78", "678.01")).isEmpty();
    }

    private List<ThreeWayMatcher.Mismatch> match(ThreeWayMatcher.InvoiceLine line, String amount, String tax, String total) {
        return matcher.match(order, List.of(line), bd(amount), bd(tax), bd(total));
    }

    private static ThreeWayMatcher.InvoiceLine line(int qty, String price) {
        BigDecimal q = BigDecimal.valueOf(qty);
        return new ThreeWayMatcher.InvoiceLine(1, q, bd(price), Money.amount(q, bd(price)));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
