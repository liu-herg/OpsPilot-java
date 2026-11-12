package com.opspilot.approval;

import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.config.JsonCodec;
import com.opspilot.dispatch.OutboxRelay;
import com.opspilot.dispatch.OutboxService;
import com.opspilot.event.EventService;
import com.opspilot.run.RunService;
import com.opspilot.run.RunStatus;
import com.opspilot.run.RunStore;
import com.opspilot.run.RunUpdate;
import com.opspilot.store.ApprovalEntity;
import com.opspilot.store.ApprovalMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static com.opspilot.config.JsonCodec.fields;

/**
 * 审批。决定一张审批单 = 改审批单 + 改运行状态 + 写事件，三件事在同一个事务里。
 *
 * <ul>
 *   <li>多人同时审批：{@code WHERE status = 'PENDING'} 的条件更新只有一个能成功</li>
 *   <li>重复提交同样的决定：返回 ALREADY_RESOLVED（幂等），不报错</li>
 *   <li>和已有决定相反（已批准又来驳回）：返回 409</li>
 * </ul>
 * 批准后的付款在事务提交之后交给后台执行：付款是远程调用，不能在事务里做；接口也不用等它。
 * "要派发这个运行"写进发件箱，和审批结果同一个事务，提交后再投递。
 */
@Service
public class ApprovalService {

    private final ApprovalMapper approvalMapper;
    private final RunStore runStore;
    private final EventService events;
    private final OutboxService outbox;
    private final OutboxRelay relay;
    private final RunService runService;
    private final TransactionTemplate tx;
    private final JsonCodec json;

    public ApprovalService(ApprovalMapper approvalMapper, RunStore runStore, EventService events,
                           OutboxService outbox, OutboxRelay relay, RunService runService,
                           TransactionTemplate tx, JsonCodec json) {
        this.approvalMapper = approvalMapper;
        this.runStore = runStore;
        this.events = events;
        this.outbox = outbox;
        this.relay = relay;
        this.runService = runService;
        this.tx = tx;
        this.json = json;
    }

    public List<ApprovalView> list(String status, int size) {
        String normalized = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase();
        return approvalMapper.selectByStatus(normalized, Math.min(Math.max(size, 1), 100)).stream()
                .map(a -> ApprovalView.of(a, json))
                .toList();
    }

    public DecisionResult decide(String approvalId, String decisionValue, String actor) {
        Decision decision = Decision.parse(decisionValue);
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor（审批人）不能为空");
        }

        Resolution r = tx.execute(s -> resolveInTransaction(approvalId, decision, actor.trim()));
        String runId = r.approval().getRunId();
        if (r.outcome() == DecisionOutcome.RESOLVED && decision == Decision.APPROVE) {
            // 事务已提交，审批结果和派发消息都已经落库；投递之前进程崩溃也没关系，
            // 定时投递会补发，READY_TO_SUBMIT 也会被恢复扫描接手
            relay.kick();
        }
        return new DecisionResult(r.outcome(), ApprovalView.of(approvalMapper.selectById(approvalId), json),
                runService.get(runId));
    }

    private record Resolution(ApprovalEntity approval, DecisionOutcome outcome) {
    }

    private Resolution resolveInTransaction(String approvalId, Decision decision, String actor) {
        LocalDateTime now = LocalDateTime.now();
        int rows = approvalMapper.resolve(approvalId, decision.approvalStatus(), actor, now);
        if (rows == 0) {
            ApprovalEntity current = approvalMapper.selectCurrent(approvalId);
            if (current == null) {
                throw new NotFoundException("审批单不存在: " + approvalId);
            }
            if (decision.approvalStatus().equals(current.getStatus())) {
                return new Resolution(current, DecisionOutcome.ALREADY_RESOLVED);
            }
            throw new ConflictException("审批单已经被 " + current.getDecisionActor() + " 处理为 "
                    + current.getStatus() + "，不能再改为 " + decision.approvalStatus(), current.getStatus());
        }

        ApprovalEntity approval = approvalMapper.selectById(approvalId);
        RunUpdate update = decision == Decision.APPROVE
                ? RunUpdate.to(RunStatus.READY_TO_SUBMIT).step("submit_payment")
                : RunUpdate.to(RunStatus.REJECTED).step("done").finished(now);
        if (!runStore.transition(approval.getRunId(), RunStatus.WAITING_APPROVAL, update)) {
            // 审批单是 PENDING，运行却不在等待审批：数据不一致。抛异常让审批单的修改一起回滚
            throw new IllegalStateException("运行 " + approval.getRunId() + " 不在 WAITING_APPROVAL 状态");
        }
        events.append(approval.getRunId(), decision == Decision.APPROVE ? "approval.approved" : "approval.rejected",
                fields("approval_id", approvalId, "actor", actor));
        if (decision == Decision.REJECT) {
            events.append(approval.getRunId(), "run.rejected", fields("approval_id", approvalId, "actor", actor));
        } else {
            outbox.enqueueDispatch(approval.getRunId(), "approval.approved");
        }
        return new Resolution(approval, DecisionOutcome.RESOLVED);
    }
}
