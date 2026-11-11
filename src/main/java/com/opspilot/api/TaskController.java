package com.opspilot.api;

import com.opspilot.run.RunService;
import com.opspilot.run.TaskCreated;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {

    private final RunService runService;

    public TaskController(RunService runService) {
        this.runService = runService;
    }

    /** 创建任务，返回 run_id；再调 POST /runs/{run_id}/start 执行。 */
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskCreated create(@Valid @RequestBody CreateTaskRequest body) {
        return runService.createTask(body.request());
    }
}
