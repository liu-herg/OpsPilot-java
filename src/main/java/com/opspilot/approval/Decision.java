package com.opspilot.approval;

public enum Decision {
    APPROVE("APPROVED"),
    REJECT("REJECTED");

    private final String approvalStatus;

    Decision(String approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public String approvalStatus() {
        return approvalStatus;
    }

    public static Decision parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("decision 不能为空，取值 approve / reject");
        }
        return switch (value.trim().toLowerCase()) {
            case "approve", "approved" -> APPROVE;
            case "reject", "rejected" -> REJECT;
            default -> throw new IllegalArgumentException("decision 取值 approve / reject，收到: " + value);
        };
    }
}
