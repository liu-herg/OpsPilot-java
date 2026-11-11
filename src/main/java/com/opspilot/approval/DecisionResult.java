package com.opspilot.approval;

import com.opspilot.run.RunView;

public record DecisionResult(DecisionOutcome outcome, ApprovalView approval, RunView run) {
}
