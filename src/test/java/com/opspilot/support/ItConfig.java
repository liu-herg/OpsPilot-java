package com.opspilot.support;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.opspilot.approval.ApprovalService;
import com.opspilot.config.JsonCodec;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.event.EventService;
import com.opspilot.idempotency.IdempotencyService;
import com.opspilot.run.PaymentSubmitter;
import com.opspilot.run.RunService;
import com.opspilot.run.RunStore;
import com.opspilot.store.StoreConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 真实 MySQL 集成测试用的最小上下文：数据源、Flyway、MyBatis-Plus、事务 + 业务服务，
 * 业务系统换成 {@link FakeGateway}（可控的故障、可统计请求次数），不启动 Web 层。
 */
@Configuration
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class,
        JacksonAutoConfiguration.class
})
@EnableConfigurationProperties(OpsPilotProperties.class)
@Import({StoreConfig.class, JsonCodec.class, RunStore.class, EventService.class, IdempotencyService.class,
        PaymentSubmitter.class, RunService.class, ApprovalService.class})
public class ItConfig {

    @Bean
    public FakeGateway fakeGateway() {
        return new FakeGateway();
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
