package com.opspilot.procure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三单匹配：采购订单（订了多少、什么价）× 收货（到了多少）× 发票（开了多少、什么价）。纯计算，不碰数据库。
 *
 * <table>
 *   <tr><td>数量</td><td>本次开票数量 ≤ 已收货 − 已开票</td><td>QTY_EXCEEDS_RECEIVED</td></tr>
 *   <tr><td>单价</td><td>|发票单价 − 订单单价| / 订单单价 ≤ 容差</td><td>PRICE_MISMATCH</td></tr>
 *   <tr><td>合计</td><td>明细合计 = 不含税金额，不含税金额 + 税额 = 价税合计（误差 ≤ 0.01）</td><td>TOTAL_MISMATCH</td></tr>
 * </table>
 */
public final class ThreeWayMatcher {

    public static final String QTY_EXCEEDS_RECEIVED = "QTY_EXCEEDS_RECEIVED";
    public static final String PRICE_MISMATCH = "PRICE_MISMATCH";
    public static final String TOTAL_MISMATCH = "TOTAL_MISMATCH";

    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** 订单行当前的情况。 */
    public record OrderLine(Long poLineId, int lineNo, BigDecimal unitPrice, BigDecimal receivedQty,
                            BigDecimal invoicedQty) {
    }

    /** 发票的一行。amount = quantity × unitPrice（由调用方算好）。 */
    public record InvoiceLine(int lineNo, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    /** 一条差异。lineNo 为空表示发票头的差异。 */
    public record Mismatch(String type, Integer lineNo, Map<String, Object> detail) {
    }

    private final BigDecimal priceTolerance;

    /** @param priceTolerance 单价容差，例如 0.01 表示 1% */
    public ThreeWayMatcher(BigDecimal priceTolerance) {
        this.priceTolerance = priceTolerance;
    }

    public List<Mismatch> match(Map<Integer, OrderLine> orderLines, List<InvoiceLine> lines,
                                BigDecimal amount, BigDecimal taxAmount, BigDecimal totalAmount) {
        List<Mismatch> result = new ArrayList<>();
        BigDecimal linesTotal = BigDecimal.ZERO;
        for (InvoiceLine line : lines) {
            OrderLine order = orderLines.get(line.lineNo());
            if (order == null) {
                throw new IllegalArgumentException("订单里没有第 " + line.lineNo() + " 行");
            }
            linesTotal = linesTotal.add(line.amount());

            BigDecimal available = order.receivedQty().subtract(order.invoicedQty());
            if (line.quantity().compareTo(available) > 0) {
                result.add(new Mismatch(QTY_EXCEEDS_RECEIVED, line.lineNo(), detail(
                        "invoice_qty", line.quantity(), "received_qty", order.receivedQty(),
                        "already_invoiced_qty", order.invoicedQty(), "available_qty", available)));
            }

            BigDecimal deviation = line.unitPrice().subtract(order.unitPrice()).abs()
                    .divide(order.unitPrice(), 6, RoundingMode.HALF_UP);
            if (deviation.compareTo(priceTolerance) > 0) {
                result.add(new Mismatch(PRICE_MISMATCH, line.lineNo(), detail(
                        "order_price", order.unitPrice(), "invoice_price", line.unitPrice(),
                        "deviation", percent(deviation), "tolerance", percent(priceTolerance))));
            }
        }
        if (linesTotal.subtract(amount).abs().compareTo(CENT) > 0) {
            result.add(new Mismatch(TOTAL_MISMATCH, null, detail(
                    "field", "amount", "lines_total", linesTotal, "amount", amount)));
        }
        BigDecimal expectedTotal = amount.add(taxAmount);
        if (expectedTotal.subtract(totalAmount).abs().compareTo(CENT) > 0) {
            result.add(new Mismatch(TOTAL_MISMATCH, null, detail(
                    "field", "total_amount", "amount_plus_tax", expectedTotal, "total_amount", totalAmount)));
        }
        return result;
    }

    private static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            map.put((String) kv[i], v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : v);
        }
        return map;
    }
}
