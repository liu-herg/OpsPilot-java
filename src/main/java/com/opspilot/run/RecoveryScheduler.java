package com.opspilot.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 故障恢复：启动时一次 + 之后定时扫描，接手所有"该有人执行、但没人持有有效租约"的运行：
 * <ul>
 *   <li>进程在执行中崩溃（RUNNING / READY_TO_SUBMIT，租约过期）</li>
 *   <li>被推迟的提交到了重试时间（retry_at 已到）</li>
 *   <li>启动后线程池满被拒绝的</li>
 * </ul>
 */
@Component
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final RunWorker worker;

    public RecoveryScheduler(RunWorker worker) {
        this.worker = worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int released = worker.releaseOwnLeases();
        if (released > 0) {
            log.warn("启动时发现 {} 个运行挂在本实例（{}）名下，上次进程应该是异常退出了，已释放租约", released,
                    worker.instanceId());
        }
        recover();
    }

    @Scheduled(fixedDelayString = "${opspilot.worker.poll-interval-ms:5000}",
            initialDelayString = "${opspilot.worker.poll-interval-ms:5000}")
    public void recover() {
        try {
            worker.recoverOnce();
        } catch (RuntimeException e) {
            // 数据库暂时不可用等：下一轮再来
            log.warn("恢复扫描失败: {}", e.getMessage());
        }
    }
}
