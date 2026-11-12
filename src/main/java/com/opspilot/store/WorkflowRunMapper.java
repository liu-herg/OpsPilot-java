package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opspilot.run.RunStatus;
import com.opspilot.run.RunUpdate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunEntity> {

    /**
     * 状态转移（CAS）：只有当前状态仍是 from 时才更新。返回 0 说明状态已经被别人改了。
     * 所有状态变化都走这一条语句，没有"先查状态、再在 Java 里判断、再更新"的写法。
     *
     * <p>后台执行时 update 带上 leaseOwner：WHERE 里多一个 {@code lease_owner = ?}（fencing），
     * 租约已经被别的实例接手的"僵尸"执行者改不动状态；成功时顺便续租（心跳）。
     */
    @Update("""
            <script>
            UPDATE workflow_run
            SET status = #{u.status}, updated_at = #{u.updatedAt}
            <if test="u.currentStep != null">, current_step = #{u.currentStep}</if>
            <if test="u.data != null">, data = #{u.data}</if>
            <if test="u.errorType != null">, error_type = #{u.errorType}, error_message = #{u.errorMessage}</if>
            <if test="u.retryAt != null">, retry_at = #{u.retryAt}</if>
            <if test="u.clearRetryAt">, retry_at = NULL</if>
            <if test="u.retryAfterSeconds != null">, retry_at = DATE_ADD(NOW(3), INTERVAL #{u.retryAfterSeconds} SECOND), retry_count = retry_count + 1</if>
            <if test="u.resetRetryCount">, retry_count = 0</if>
            <if test="u.leaseOwner != null">, lease_expires_at = DATE_ADD(NOW(3), INTERVAL #{u.leaseSeconds} SECOND)</if>
            <if test="u.startedAt != null">, started_at = #{u.startedAt}</if>
            <if test="u.finishedAt != null">, finished_at = #{u.finishedAt}</if>
            WHERE id = #{id} AND status = #{from}
            <if test="u.leaseOwner != null">AND lease_owner = #{u.leaseOwner}</if>
            </script>
            """)
    int compareAndSet(@Param("id") String id, @Param("from") RunStatus from, @Param("u") RunUpdate update);

    /**
     * 分配事件序号第一步：原子 +1。这条 UPDATE 会持有该运行的行锁直到事务提交，
     * 并发追加事件的事务在这里排队，所以序号不会重复。
     */
    @Update("UPDATE workflow_run SET event_sequence = event_sequence + 1 WHERE id = #{id}")
    int incrementEventSequence(@Param("id") String id);

    /** 分配事件序号第二步：读回刚加完的值（同一事务内能看到自己的修改）。 */
    @Select("SELECT event_sequence FROM workflow_run WHERE id = #{id}")
    Integer selectEventSequence(@Param("id") String id);

    // ---------------------------------------------------------------- 执行租约

    /**
     * 按 id 抢执行租约（启动、审批通过、手动继续之后立即执行用）。
     * 没人持有或租约已过期才能抢到；判断和占有在同一条 UPDATE 里。
     */
    @Update("""
            UPDATE workflow_run
            SET lease_owner = #{owner}, lease_expires_at = DATE_ADD(NOW(3), INTERVAL #{seconds} SECOND)
            WHERE id = #{id}
              AND status IN ('RUNNING', 'READY_TO_SUBMIT')
              AND (lease_owner IS NULL OR lease_expires_at <= NOW(3))
            """)
    int claimLease(@Param("id") String id, @Param("owner") String owner, @Param("seconds") int seconds);

    /**
     * 恢复扫描第一步：找出"该有人执行、但现在没人在执行"的运行并加锁。
     * SKIP LOCKED：别的实例正在领的行直接跳过，多个实例同时扫描时各领各的，不会互相等待。
     * 必须在事务里调用，第二步 {@link #leaseLocked} 在同一个事务里把它们标记为自己的。
     *
     * <p>graceSeconds（MQ 派发时 &gt; 0）：刚写过派发消息的运行先不碰，它多半正在 RabbitMQ 里排队、马上会被消费者领走；
     * 不加这个条件，扫描会把"排队中"误认为"没人管"，和消费者抢活。过了宽限期还没人领（MQ 挂了、消息放弃了），扫描再接手。
     * 有 retry_at 的运行不受影响：它是执行者自己安排的重试，不在 MQ 里。
     * 子查询不加锁（FOR UPDATE OF r 只锁 workflow_run）。
     */
    @Select("""
            SELECT r.id FROM workflow_run r
            WHERE r.status IN ('RUNNING', 'READY_TO_SUBMIT')
              AND (r.lease_owner IS NULL OR r.lease_expires_at <= NOW(3))
              AND (r.retry_at IS NULL OR r.retry_at <= NOW(3))
              AND (r.retry_at IS NOT NULL OR NOT EXISTS (
                    SELECT 1 FROM outbox_message o
                    WHERE o.message_key = r.id
                      AND o.created_at > DATE_SUB(NOW(3), INTERVAL #{graceSeconds} SECOND)))
            ORDER BY r.updated_at
            LIMIT #{limit}
            FOR UPDATE OF r SKIP LOCKED
            """)
    List<String> lockClaimable(@Param("limit") int limit, @Param("graceSeconds") int graceSeconds);

    @Update("""
            <script>
            UPDATE workflow_run
            SET lease_owner = #{owner}, lease_expires_at = DATE_ADD(NOW(3), INTERVAL #{seconds} SECOND)
            WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int leaseLocked(@Param("ids") List<String> ids, @Param("owner") String owner, @Param("seconds") int seconds);

    /** 执行结束（不管成功失败）释放租约；只能释放自己的。 */
    @Update("UPDATE workflow_run SET lease_owner = NULL, lease_expires_at = NULL WHERE id = #{id} AND lease_owner = #{owner}")
    int releaseLease(@Param("id") String id, @Param("owner") String owner);

    /** 实例启动时：名下还挂着的租约一定是上一次进程留下的（进程已经死了），直接释放，不用等过期。 */
    @Update("UPDATE workflow_run SET lease_owner = NULL, lease_expires_at = NULL WHERE lease_owner = #{owner}")
    int releaseAllOwnedBy(@Param("owner") String owner);

    @Select("""
            <script>
            SELECT * FROM workflow_run
            <where>
              <if test="status != null">status = #{status}</if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{size}
            </script>
            """)
    List<WorkflowRunEntity> selectRecent(@Param("status") String status, @Param("size") int size);
}
