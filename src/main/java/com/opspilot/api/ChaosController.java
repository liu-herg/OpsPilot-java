package com.opspilot.api;

import com.opspilot.config.JsonCodec;
import com.opspilot.gateway.ChaosSwitch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 故障注入（演示用）。需要 CHAOS_ENABLED=true 启动。 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private final ChaosSwitch chaos;

    public ChaosController(ChaosSwitch chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    public Map<String, Object> status() {
        return JsonCodec.fields("enabled", chaos.isEnabled(), "pending_submit_response_drops", chaos.pendingDrops(),
                "crash_after_next_submit", chaos.crashArmed());
    }

    /** 接下来 times 次付款请求：财务系统正常处理，但响应被丢弃（模拟超时）。 */
    @PostMapping("/drop-submit-response")
    public Map<String, Object> dropSubmitResponse(@RequestParam(defaultValue = "1") int times) {
        if (!chaos.isEnabled()) {
            throw new IllegalArgumentException("故障注入未开启，请用 CHAOS_ENABLED=true 启动应用");
        }
        chaos.dropNextSubmitResponses(times);
        return status();
    }

    /** 下一次付款请求成功后，进程立即崩溃（容器配置了自动重启，重启后由故障恢复接手）。 */
    @PostMapping("/crash-after-submit")
    public Map<String, Object> crashAfterSubmit() {
        if (!chaos.isEnabled()) {
            throw new IllegalArgumentException("故障注入未开启，请用 CHAOS_ENABLED=true 启动应用");
        }
        chaos.crashAfterNextSubmit();
        return status();
    }
}
