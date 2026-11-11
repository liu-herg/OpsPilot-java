package com.opspilot.gateway;

import com.opspilot.config.OpsPilotProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故障注入（仅演示用，默认关闭）：
 * <ul>
 *   <li>让接下来 N 次付款请求"发出去了、但响应丢了"，模拟网络超时，演示对账如何避免重复付款</li>
 *   <li>下一次付款成功后进程立刻崩溃（还没来得及记录结果），演示重启后的故障恢复</li>
 * </ul>
 */
@Component
public class ChaosSwitch {

    private final boolean enabled;
    private final AtomicInteger dropSubmitResponses = new AtomicInteger();
    private final AtomicBoolean crashAfterSubmit = new AtomicBoolean();

    public ChaosSwitch(OpsPilotProperties properties) {
        this.enabled = properties.chaos().enabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void dropNextSubmitResponses(int times) {
        if (!enabled) {
            throw new IllegalStateException("故障注入未开启（opspilot.chaos.enabled=false）");
        }
        dropSubmitResponses.set(Math.max(0, times));
    }

    public void crashAfterNextSubmit() {
        if (!enabled) {
            throw new IllegalStateException("故障注入未开启（opspilot.chaos.enabled=false）");
        }
        crashAfterSubmit.set(true);
    }

    public boolean crashArmed() {
        return crashAfterSubmit.get();
    }

    public int pendingDrops() {
        return dropSubmitResponses.get();
    }

    /**
     * 如果设置了"付款后崩溃"，直接终止 JVM：halt 不执行 shutdown hook、不跑 finally，
     * 和进程被 kill -9 / 机器断电一样，数据库里的租约、幂等记录都停留在崩溃那一刻。
     */
    void crashIfArmed(String poNumber) {
        if (enabled && crashAfterSubmit.compareAndSet(true, false)) {
            System.err.println("[chaos] 付款 " + poNumber + " 已提交成功，进程立即崩溃（模拟 kill -9）");
            System.err.flush();
            Runtime.getRuntime().halt(137);
        }
    }

    /** 如果还有待丢弃的次数，消耗一次并返回 true。 */
    boolean consumeDrop() {
        return enabled && dropSubmitResponses.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }
}
