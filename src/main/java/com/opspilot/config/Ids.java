package com.opspilot.config;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    /** 例如 run-3f9a1c2b7d4e */
    public static String next(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
