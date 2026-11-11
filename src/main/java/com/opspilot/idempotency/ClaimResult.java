package com.opspilot.idempotency;

import com.opspilot.store.IdempotencyRecordEntity;

public record ClaimResult(ClaimOutcome outcome, IdempotencyRecordEntity record) {

    public boolean mayExecute() {
        return outcome == ClaimOutcome.ACQUIRED || outcome == ClaimOutcome.RECLAIMED;
    }
}
