package com.opspilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("opspilot")
public record OpsPilotProperties(
        @DefaultValue Systems systems,
        @DefaultValue("10000") long approvalThreshold,
        @DefaultValue("30") int idempotencyLeaseSeconds,
        @DefaultValue Chaos chaos,
        @DefaultValue Worker worker,
        @DefaultValue Dispatch dispatch) {

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

    /**
     * 派发：运行被启动 / 审批通过 / 手动继续之后，怎么交给执行者。
     *
     * @param mode            {@code local}：投递器直接交给本实例的线程池（不需要 MQ，P2 的行为）；
     *                        {@code mq}：投递到 RabbitMQ，所有实例的消费者一起消费
     * @param relayPollMs     投递器兜底扫描间隔（正常情况下事务提交后立即触发投递，不等扫描）
     * @param relayBatchSize  投递器每次最多领取多少条
     * @param claimSeconds    投递器领取后多久没确认就允许别人重新领取（投递器崩溃时）
     * @param maxAttempts     最多尝试投递几次，超过就放弃（运行由恢复扫描兜底）
     * @param confirmTimeoutMs 等 RabbitMQ 确认（publisher confirm）的超时
     * @param retentionDays   已投递的消息保留几天
     * @param scanGraceSeconds MQ 派发时，恢复扫描对"刚写过派发消息"的运行等多久再接手（给消费者留时间）
     */
    public record Dispatch(
            @DefaultValue("local") String mode,
            @DefaultValue("1000") long relayPollMs,
            @DefaultValue("50") int relayBatchSize,
            @DefaultValue("30") int claimSeconds,
            @DefaultValue("20") int maxAttempts,
            @DefaultValue("5000") long confirmTimeoutMs,
            @DefaultValue("7") int retentionDays,
            @DefaultValue("15") int scanGraceSeconds) {
    }

    /** 故障注入，只用于演示，默认关闭。 */
    public record Chaos(@DefaultValue("false") boolean enabled) {
    }
}
