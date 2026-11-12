package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GoodsReceiptMapper extends BaseMapper<GoodsReceiptEntity> {

    @Select("SELECT * FROM goods_receipt WHERE po_id = #{poId} ORDER BY id")
    List<GoodsReceiptEntity> selectByOrder(@Param("poId") Long poId);
}
