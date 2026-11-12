package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InvoiceLineMapper extends BaseMapper<InvoiceLineEntity> {

    @Select("SELECT * FROM invoice_line WHERE invoice_id = #{invoiceId} ORDER BY id")
    List<InvoiceLineEntity> selectByInvoice(@Param("invoiceId") Long invoiceId);

    @Update("UPDATE invoice_line SET occupied = 1 WHERE id = #{id} AND occupied = 0")
    int markOccupied(@Param("id") Long id);
}
