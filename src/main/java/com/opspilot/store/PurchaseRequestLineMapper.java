package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PurchaseRequestLineMapper extends BaseMapper<PurchaseRequestLineEntity> {

    @Select("SELECT * FROM purchase_request_line WHERE request_id = #{requestId} ORDER BY line_no")
    List<PurchaseRequestLineEntity> selectByRequest(@Param("requestId") Long requestId);
}
