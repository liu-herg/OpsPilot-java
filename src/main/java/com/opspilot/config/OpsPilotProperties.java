package com.opspilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("opspilot")
public record OpsPilotProperties(
        @DefaultValue Systems systems,
        @DefaultValue("10000") long approvalThreshold,
        @DefaultValue("30") int idempotencyLeaseSeconds,
        @DefaultValue Chaos chaos) {

    /** 三个被自动化的业务系统地址。 */
    public record Systems(
            @DefaultValue("http://localhost:8101") String procurementUrl,
            @DefaultValue("http://localhost:8102") String invoiceUrl,
            @DefaultValue("http://localhost:8103") String financeUrl,
            @DefaultValue("2000") int connectTimeoutMs,
            @DefaultValue("5000") int readTimeoutMs) {
    }

    /** 故障注入，只用于演示，默认关闭。 */
    public record Chaos(@DefaultValue("false") boolean enabled) {
    }
}
