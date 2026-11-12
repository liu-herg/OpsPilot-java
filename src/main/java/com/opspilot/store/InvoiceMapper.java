package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InvoiceMapper extends BaseMapper<InvoiceEntity> {

    @Select("SELECT * FROM invoice WHERE id = #{id} FOR UPDATE")
    InvoiceEntity lockById(@Param("id") Long id);

    @Select("SELECT * FROM invoice WHERE po_id = #{poId} ORDER BY id")
    List<InvoiceEntity> selectByOrder(@Param("poId") Long poId);

    @Update("UPDATE invoice SET status = #{to}, updated_at = NOW(3) WHERE id = #{id} AND status = #{from}")
    int transition(@Param("id") Long id, @Param("from") String from, @Param("to") String to);
}
