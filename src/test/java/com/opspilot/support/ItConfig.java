package com.opspilot.support;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.opspilot.approval.ApprovalService;
import com.opspilot.config.JsonCodec;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.dispatch.LocalDispatchPublisher;
import com.opspilot.dispatch.OutboxRelay;
import com.opspilot.dispatch.OutboxService;
import com.opspilot.event.EventService;
import com.opspilot.procure.BizNumberService;
import com.opspilot.procure.PurchaseOrderService;
import com.opspilot.procure.PurchaseRequestService;
import com.opspilot.procure.SupplierService;
import com.opspilot.idempotency.IdempotencyService;
import com.opspilot.run.PaymentSubmitter;
import com.opspilot.run.RunExecutor;
import com.opspilot.run.RunService;
import com.opspilot.run.RunStore;
import com.opspilot.run.RunWorker;
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
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * 真实 MySQL 集成测试用的最小上下文：数据源、Flyway、MyBatis-Plus、事务 + 业务服务，
 * 业务系统换成 {@link FakeGateway}（可控的故障、可统计请求次数），不启动 Web 层。
 * 执行线程池换成同步执行器：dispatch 之后当前线程直接跑完，测试结果是确定的；
 * 恢复扫描不定时运行，由测试手动调 {@code RunWorker.recoverOnce()}。
 * 发件箱的即时投递也换成同步执行器：start() 返回时消息已经投递、运行已经执行完；
 * 定时投递（OutboxScheduler）不启动。默认 dispatch.mode=local，投递就是交给上面的同步执行器。
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
        PaymentSubmitter.class, RunExecutor.class, RunWorker.class, RunService.class, ApprovalService.class,
        OutboxService.class, OutboxRelay.class, LocalDispatchPublisher.class,
        BizNumberService.class, SupplierService.class, PurchaseOrderService.class, PurchaseRequestService.class})
public class ItConfig {

    @Bean
    public FakeGateway fakeGateway() {
        return new FakeGateway();
    }

    @Bean("runTaskExecutor")
    public TaskExecutor runTaskExecutor() {
        return new SyncTaskExecutor();
    }

    @Bean("outboxRelayExecutor")
    public TaskExecutor outboxRelayExecutor() {
        return new SyncTaskExecutor();
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
