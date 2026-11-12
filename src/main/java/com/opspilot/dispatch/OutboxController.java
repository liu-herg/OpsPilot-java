package com.opspilot.dispatch;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.OutboxMessageMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 发件箱状态（演示和排查用）：各状态的消息数 + 本实例消费派发消息的统计。 */
@RestController
@RequestMapping("/outbox")
public class OutboxController {

    private final OutboxMessageMapper mapper;
    private final MeterRegistry meterRegistry;
    private final String mode;

    public OutboxController(OutboxMessageMapper mapper, MeterRegistry meterRegistry, OpsPilotProperties properties) {
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
        this.mode = properties.dispatch().mode();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("PENDING", 0L);
        counts.put("SENT", 0L);
        counts.put("DEAD", 0L);
        for (Map<String, Object> row : mapper.countByStatus()) {
            counts.put(String.valueOf(row.get("status")), ((Number) row.get("cnt")).longValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("outbox", counts);
        result.put("consumed_executed", counter("executed"));
        result.put("consumed_skipped", counter("skipped"));
        return result;
    }

    private long counter(String outcome) {
        Counter c = meterRegistry.find("opspilot.dispatch.consumed").tag("outcome", outcome).counter();
        return c == null ? 0 : (long) c.count();
    }
}
