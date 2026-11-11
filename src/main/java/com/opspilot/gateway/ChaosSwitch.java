package com.opspilot.gateway;

import com.opspilot.config.OpsPilotProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故障注入（仅演示用，默认关闭）：让接下来 N 次付款请求"发出去了、但响应丢了"，
 * 模拟网络超时，用来演示对账如何避免重复付款。
 */
@Component
public class ChaosSwitch {

    private final boolean enabled;
    private final AtomicInteger dropSubmitResponses = new AtomicInteger();

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

    public int pendingDrops() {
        return dropSubmitResponses.get();
    }

    /** 如果还有待丢弃的次数，消耗一次并返回 true。 */
    boolean consumeDrop() {
        return enabled && dropSubmitResponses.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }
}
