package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface OutboxMessageMapper extends BaseMapper<OutboxMessageEntity> {

    /** 写入一条待投递消息。时间都用数据库时钟，和投递器扫描时的 NOW(3) 是同一个时钟。 */
    @Insert("""
            INSERT INTO outbox_message (topic, message_key, reason, status, attempts, next_attempt_at, created_at)
            VALUES (#{m.topic}, #{m.messageKey}, #{m.reason}, 'PENDING', 0, NOW(3), NOW(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "m.id", keyColumn = "id")
    int enqueue(@Param("m") OutboxMessageEntity message);

    /**
     * 投递器领取第一步：锁住一批到时间的待投递消息。
     * SKIP LOCKED：多个投递器（多实例、或同一实例的定时任务和即时触发）同时扫描时各领各的，不会重复投递，也不互相等待。
     * 必须在事务里调用，第二步 {@link #claim} 在同一个事务里把它们推后。
     */
    @Select("""
            SELECT id FROM outbox_message
            WHERE status = 'PENDING' AND next_attempt_at <= NOW(3)
            ORDER BY id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<Long> lockDue(@Param("limit") int limit);

    /**
     * 投递器领取第二步：把 next_attempt_at 推后 seconds 秒（相当于领取租约），尝试次数 +1。
     * 事务提交后行锁就释放了，真正的投递在事务外做；投递器如果在投递途中崩溃，
     * 到时间后消息会被重新领取、再投递一次（至少一次投递，消费端要幂等）。
     */
    @Update("""
            <script>
            UPDATE outbox_message
            SET next_attempt_at = DATE_ADD(NOW(3), INTERVAL #{seconds} SECOND), attempts = attempts + 1
            WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int claim(@Param("ids") List<Long> ids, @Param("seconds") int seconds);

    @Select("""
            <script>
            SELECT * FROM outbox_message
            WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id
            </script>
            """)
    List<OutboxMessageEntity> selectByIdList(@Param("ids") List<Long> ids);

    /** MQ 确认收到：标记为已投递。只改还在 PENDING 的（DEAD 的不复活）。 */
    @Update("UPDATE outbox_message SET status = 'SENT', sent_at = NOW(3), last_error = NULL WHERE id = #{id} AND status = 'PENDING'")
    int markSent(@Param("id") Long id);

    /** 投递失败：推迟 delaySeconds 秒后再试；尝试次数达到上限就放弃（DEAD），运行由恢复扫描兜底执行。 */
    @Update("""
            UPDATE outbox_message
            SET status = IF(attempts >= #{maxAttempts}, 'DEAD', 'PENDING'),
                next_attempt_at = DATE_ADD(NOW(3), INTERVAL #{delaySeconds} SECOND),
                last_error = #{error}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markFailed(@Param("id") Long id, @Param("delaySeconds") int delaySeconds, @Param("error") String error,
                   @Param("maxAttempts") int maxAttempts);

    /** 清理已投递的旧消息，每次最多删 limit 条，避免一次删太多长时间锁表。 */
    @Delete("DELETE FROM outbox_message WHERE status = 'SENT' AND sent_at < DATE_SUB(NOW(3), INTERVAL #{days} DAY) LIMIT #{limit}")
    int deleteSentBefore(@Param("days") int days, @Param("limit") int limit);

    @Select("SELECT status, COUNT(*) AS cnt FROM outbox_message GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT * FROM outbox_message WHERE message_key = #{key} ORDER BY id")
    List<OutboxMessageEntity> selectByKey(@Param("key") String key);
}
