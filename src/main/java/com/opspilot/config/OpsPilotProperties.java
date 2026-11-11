package com.opspilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("opspilot")
public record OpsPilotProperties(
        @DefaultValue Systems systems,
        @DefaultValue("10000") long approvalThreshold,
        @DefaultValue("30") int idempotencyLeaseSeconds,
        @DefaultValue Chaos chaos,
        @DefaultValue Worker worker) {

    /** 三个被自动化的业务系统地址。 */
    public record Systems(
            @DefaultValue("http://localhost:8101") String procurementUrl,
            @DefaultValue("http://localhost:8102") String invoiceUrl,
            @DefaultValue("http://localhost:8103") String financeUrl,
            @DefaultValue("2000") int connectTimeoutMs,
            @DefaultValue("5000") int readTimeoutMs) {
    }

    /**
     * 后台执行与故障恢复。
     *
     * @param instanceId     实例 ID，为空时用主机名（容器里就是容器 ID，重启后不变）
     * @param leaseSeconds   执行租约时长；实例崩溃后最多这么久，运行会被别的实例接手
     * @param pollIntervalMs 恢复扫描间隔
     * @param batchSize      每次扫描最多领多少个运行
     * @param threads        执行线程数
     * @param maxReadRetries 只读步骤（查采购单 / 发票）因外部系统故障最多推迟几次，超过就失败
     */
    public record Worker(
            @DefaultValue("") String instanceId,
            @DefaultValue("60") int leaseSeconds,
            @DefaultValue("5000") long pollIntervalMs,
            @DefaultValue("10") int batchSize,
            @DefaultValue("4") int threads,
            @DefaultValue("5") int maxReadRetries) {
    }

    /** 故障注入，只用于演示，默认关闭。 */
    public record Chaos(@DefaultValue("false") boolean enabled) {
    }
}
