package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RunEventMapper extends BaseMapper<RunEventEntity> {

    /** 时间线：按序号升序；after 用于断线重连后只取新事件。走唯一索引 (run_id, sequence)。 */
    @Select("SELECT * FROM run_event WHERE run_id = #{runId} AND sequence > #{after} ORDER BY sequence")
    List<RunEventEntity> selectByRun(@Param("runId") String runId, @Param("after") int after);
}
