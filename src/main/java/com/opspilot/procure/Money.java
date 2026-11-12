package com.opspilot.procure;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 金额和数量：一律 BigDecimal，两位小数。不用 double，0.1 + 0.2 在 double 里不等于 0.3。 */
public final class Money {

    private Money() {
    }

    /** 数量：必须大于 0，最多两位小数。 */
    public static BigDecimal quantity(BigDecimal value, String field) {
        return positive(value, field);
    }

    /** 单价：必须大于 0，最多两位小数。 */
    public static BigDecimal price(BigDecimal value, String field) {
        return positive(value, field);
    }

    /** 金额（税额等）：可以为 0，不能为负，最多两位小数。 */
    public static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + " 最多两位小数");
        }
    }

    /** 数量 × 单价，四舍五入到分。 */
    public static BigDecimal amount(BigDecimal quantity, BigDecimal unitPrice) {
        return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + " 最多两位小数");
        }
    }
}
