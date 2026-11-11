package com.opspilot.event;

import com.opspilot.api.NotFoundException;
import com.opspilot.config.JsonCodec;
import com.opspilot.store.RunEventEntity;
import com.opspilot.store.RunEventMapper;
import com.opspilot.store.WorkflowRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 运行事件（时间线）。每个运行内的事件序号从 1 开始连续递增、不重复，
 * 客户端断线重连后可以用"我已经看到第 N 条"来补拉。
 */
@Service
public class EventService {

    private final WorkflowRunMapper runMapper;
    private final RunEventMapper eventMapper;
    private final JsonCodec json;

    public EventService(WorkflowRunMapper runMapper, RunEventMapper eventMapper, JsonCodec json) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.json = json;
    }

    /**
     * 追加一条事件。在调用方的事务里执行（没有事务时自己开一个），
     * 所以"状态改变 + 对应的事件"要么都提交，要么都不提交。
     *
     * <p>序号分配：先 UPDATE event_sequence + 1（拿到该运行的行锁），再读回新值。
     * 不能用 SELECT MAX(sequence) + 1：两个并发事务会读到同一个 MAX，然后插入时撞唯一索引。
     */
    @Transactional
    public RunEventEntity append(String runId, String eventType, Map<String, Object> payload) {
        if (runMapper.incrementEventSequence(runId) == 0) {
            throw new NotFoundException("运行不存在: " + runId);
        }
        int sequence = runMapper.selectEventSequence(runId);
        RunEventEntity event = new RunEventEntity();
        event.setRunId(runId);
        event.setSequence(sequence);
        event.setEventType(eventType);
        event.setPayload(json.write(payload == null ? Map.of() : payload));
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
        return event;
    }

    public List<RunEventEntity> list(String runId, int afterSequence) {
        return eventMapper.selectByRun(runId, Math.max(0, afterSequence));
    }
}
