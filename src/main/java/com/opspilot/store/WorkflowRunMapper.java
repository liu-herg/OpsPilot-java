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
            <if test="u.startedAt != null">, started_at = #{u.startedAt}</if>
            <if test="u.finishedAt != null">, finished_at = #{u.finishedAt}</if>
            WHERE id = #{id} AND status = #{from}
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
