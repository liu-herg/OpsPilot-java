package com.opspilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class ExecutorConfig {

    /**
     * 执行运行的线程池。队列有界、满了直接拒绝（RunWorker 会释放租约，交给下一轮恢复扫描），
     * 而不是 CallerRunsPolicy：调用方可能是处理 HTTP 请求的线程，不能让它去跑一整个流程。
     * 停机时等正在执行的运行跑完（最多 30 秒），没跑完的靠租约过期后被接手。
     */
    @Bean("runTaskExecutor")
    public ThreadPoolTaskExecutor runTaskExecutor(OpsPilotProperties properties) {
        int threads = properties.worker().threads();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("run-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * 发件箱即时投递的线程：1 个线程、队列长度 1，满了直接丢弃（DiscardPolicy）。
     * 投递一轮会把所有到期的消息都领走，所以"正在投递 + 排队一轮"就够了，
     * 再多的触发都是重复劳动；万一真漏了，定时扫描每秒还会兜底一次。
     */
    @Bean("outboxRelayExecutor")
    public ThreadPoolTaskExecutor outboxRelayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("outbox-relay-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
