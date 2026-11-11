package com.opspilot.api;

import com.opspilot.run.EventView;
import com.opspilot.run.RunService;
import com.opspilot.run.RunView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/{runId}/start")
    public RunView start(@PathVariable String runId) {
        return runService.start(runId);
    }

    /** 继续提交停在 READY_TO_SUBMIT 的运行（P2 由调度器自动做）。 */
    @PostMapping("/{runId}/resume")
    public RunView resume(@PathVariable String runId) {
        return runService.resume(runId);
    }
}
