package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ApprovalMapper extends BaseMapper<ApprovalEntity> {

    /** 条件更新：只有还是 PENDING 才能被决定。并发审批时只有一个能更新成功。 */
    @Update("""
            UPDATE approval
            SET status = #{status}, decision_actor = #{actor}, resolved_at = #{now}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int resolve(@Param("id") String id, @Param("status") String status,
                @Param("actor") String actor, @Param("now") LocalDateTime now);

    /**
     * 加锁读（当前读）。条件更新失败后要看"别人决定成了什么"，必须读最新提交的版本：
     * 可重复读隔离级别下，如果本事务之前做过普通 SELECT，再普通 SELECT 读到的是旧快照（还是 PENDING）。
     */
    @Select("SELECT * FROM approval WHERE id = #{id} FOR SHARE")
    ApprovalEntity selectCurrent(@Param("id") String id);

    @Select("SELECT * FROM approval WHERE status = #{status} ORDER BY created_at LIMIT #{size}")
    List<ApprovalEntity> selectByStatus(@Param("status") String status, @Param("size") int size);
}
