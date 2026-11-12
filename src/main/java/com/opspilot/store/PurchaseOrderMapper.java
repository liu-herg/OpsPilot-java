package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrderEntity> {

    /**
     * 锁住订单行（当前读）。收货、取消、开票都先锁订单：同一张订单的这些操作串行，不同订单之间互不影响。
     * 所有操作都按"先订单、再订单明细"的顺序加锁，不会互相死锁。
     */
    @Select("SELECT * FROM purchase_order WHERE id = #{id} FOR UPDATE")
    PurchaseOrderEntity lockById(@Param("id") Long id);

    @Select("SELECT * FROM purchase_order WHERE po_number = #{poNumber}")
    PurchaseOrderEntity selectByNumber(@Param("poNumber") String poNumber);

    /** 按订单号直接加锁（不先做普通查询，避免在加锁前固定读视图）。 */
    @Select("SELECT * FROM purchase_order WHERE po_number = #{poNumber} FOR UPDATE")
    PurchaseOrderEntity lockByNumber(@Param("poNumber") String poNumber);

    @Update("UPDATE purchase_order SET status = #{to}, updated_at = NOW(3) WHERE id = #{id} AND status = #{from}")
    int transition(@Param("id") Long id, @Param("from") String from, @Param("to") String to);
}
