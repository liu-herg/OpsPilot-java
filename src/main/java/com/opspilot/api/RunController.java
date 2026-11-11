package com.opspilot.api;

import com.opspilot.run.EventView;
import com.opspilot.run.RunService;
import com.opspilot.run.RunView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @GetMapping
    public List<RunView> list(@RequestParam(required = false) String status,
                              @RequestParam(defaultValue = "50") int size) {
        return runService.list(status, size);
    }

    @GetMapping("/{runId}")
    public RunView get(@PathVariable String runId) {
        return runService.get(runId);
    }

    /** 时间线；after=N 只返回序号大于 N 的事件（断线重连补拉）。 */
    @GetMapping("/{runId}/events")
    public List<EventView> events(@PathVariable String runId, @RequestParam(defaultValue = "0") int after) {
        return runService.events(runId, after);
    }

    /** 202：已经交给后台执行，用 GET /runs/{id} 或 /events 查看进度。 */
    @PostMapping("/{runId}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView start(@PathVariable String runId) {
        return runService.start(runId);
    }

    /** 不等退避时间，马上重试（正常情况下恢复扫描会按 retry_at 自动重试）。 */
    @PostMapping("/{runId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView resume(@PathVariable String runId) {
        return runService.resume(runId);
    }
}
