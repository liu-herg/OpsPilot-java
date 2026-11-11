package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 幂等记录。时间一律用数据库的 NOW(3)，不用应用服务器的时间：
 * 多个实例的时钟可能不一致，租约是否过期必须用同一个时钟判断。
 */
@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {

    /** 抢占第一步：直接插入。主键冲突（DuplicateKeyException）说明已经有人登记过这笔付款。 */
    @Insert("""
            INSERT INTO idempotency_record
                (idem_key, operation, status, owner_run_id, lease_expires_at, attempt, created_at, updated_at)
            VALUES
                (#{key}, #{operation}, 'PENDING', #{runId}, DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND), 1, NOW(3), NOW(3))
            """)
    int insertPending(@Param("key") String key, @Param("operation") String operation,
                      @Param("runId") String runId, @Param("leaseSeconds") int leaseSeconds);

    /**
     * 抢占第二步：租约已过期的 PENDING 记录可以被接手（原持有者大概率已崩溃）。
     * "是否过期"的判断和"改成我的"在同一条 UPDATE 里完成，多个接手者只有一个能成功。
     */
    @Update("""
            UPDATE idempotency_record
            SET owner_run_id = #{runId},
                lease_expires_at = DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND),
                attempt = attempt + 1,
                updated_at = NOW(3)
            WHERE idem_key = #{key} AND status = 'PENDING' AND lease_expires_at <= NOW(3)
            """)
    int reclaimExpired(@Param("key") String key, @Param("runId") String runId,
                       @Param("leaseSeconds") int leaseSeconds);

    /** 确认付款已在财务系统存在后标记完成，保存结果供重复请求直接返回。 */
    @Update("""
            UPDATE idempotency_record
            SET status = 'COMPLETED', result = #{result}, lease_expires_at = NULL, updated_at = NOW(3)
            WHERE idem_key = #{key} AND status = 'PENDING'
            """)
    int complete(@Param("key") String key, @Param("result") String result);

    /**
     * 确定请求没有发出去（连接失败）或被对方明确拒绝时，删除记录让下次可以立即重试。
     * 只能删自己持有的；结果不确定（超时）时绝不能删，要等对账。
     */
    @Delete("""
            DELETE FROM idempotency_record
            WHERE idem_key = #{key} AND owner_run_id = #{runId} AND status = 'PENDING'
            """)
    int release(@Param("key") String key, @Param("runId") String runId);
}
