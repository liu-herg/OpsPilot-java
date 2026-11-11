package com.opspilot.approval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionTest {

    @Test
    void parsesCaseInsensitively() {
        assertThat(Decision.parse("approve")).isEqualTo(Decision.APPROVE);
        assertThat(Decision.parse(" Reject ")).isEqualTo(Decision.REJECT);
        assertThat(Decision.APPROVE.approvalStatus()).isEqualTo("APPROVED");
    }

    @Test
    void rejectsUnknownValues() {
        assertThatThrownBy(() -> Decision.parse("maybe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Decision.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
