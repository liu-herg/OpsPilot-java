package com.opspilot.procure;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void amountIsRoundedToCents() {
        assertThat(Money.amount(new BigDecimal("3"), new BigDecimal("33.33"))).isEqualByComparingTo("99.99");
        assertThat(Money.amount(new BigDecimal("0.5"), new BigDecimal("0.01"))).isEqualByComparingTo("0.01");
        // double 下 0.1 * 3 = 0.30000000000000004
        assertThat(Money.amount(new BigDecimal("3"), new BigDecimal("0.10"))).isEqualByComparingTo("0.30");
    }

    @Test
    void quantityMustBePositiveWithAtMostTwoDecimals() {
        assertThat(Money.quantity(new BigDecimal("2.5"), "数量").scale()).isEqualTo(2);
        assertThatThrownBy(() -> Money.quantity(new BigDecimal("0"), "数量")).hasMessageContaining("大于 0");
        assertThatThrownBy(() -> Money.quantity(new BigDecimal("-1"), "数量")).hasMessageContaining("大于 0");
        assertThatThrownBy(() -> Money.quantity(new BigDecimal("1.234"), "数量")).hasMessageContaining("两位小数");
    }

    @Test
    void bankAccountIsMasked() {
        assertThat(SupplierView.mask("6222000011112222")).isEqualTo("****2222");
        assertThat(SupplierView.mask("123")).isEqualTo("123");
    }
}
