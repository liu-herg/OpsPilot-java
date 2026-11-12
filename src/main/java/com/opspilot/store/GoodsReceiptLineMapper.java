package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GoodsReceiptLineMapper extends BaseMapper<GoodsReceiptLineEntity> {

    @Select("SELECT * FROM goods_receipt_line WHERE gr_id = #{grId} ORDER BY id")
    List<GoodsReceiptLineEntity> selectByReceipt(@Param("grId") Long grId);
}
