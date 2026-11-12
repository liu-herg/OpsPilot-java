package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PurchaseOrderLineMapper extends BaseMapper<PurchaseOrderLineEntity> {

    @Select("SELECT * FROM purchase_order_line WHERE po_id = #{poId} ORDER BY line_no")
    List<PurchaseOrderLineEntity> selectByOrder(@Param("poId") Long poId);

    /**
     * 当前读 + 加锁读订单明细。要基于"已收 / 已开票数量"做判断时必须用它：
     * 可重复读隔离级别下，事务里第一次普通 SELECT 就固定了读视图，之后哪怕已经锁住订单，
     * 普通 SELECT 读到的仍是加锁之前的旧值（P4 并发开票测试里踩过）。调用前应已锁住订单，加锁顺序不变。
     */
    @Select("SELECT * FROM purchase_order_line WHERE po_id = #{poId} ORDER BY line_no FOR UPDATE")
    List<PurchaseOrderLineEntity> lockByOrder(@Param("poId") Long poId);

    /**
     * 收货：条件递增。加上本次数量不超过订购数量才成功，返回 0 表示会超收。
     * 数量检查放在 UPDATE 的 WHERE 里由数据库判断，而不是"先查已收多少、Java 里比较、再写回"。
     */
    @Update("""
            UPDATE purchase_order_line SET received_qty = received_qty + #{qty}
            WHERE id = #{id} AND po_id = #{poId} AND received_qty + #{qty} <= quantity
            """)
    int receive(@Param("id") Long id, @Param("poId") Long poId, @Param("qty") BigDecimal qty);

    /**
     * 开票占用：条件递增，加上本次数量不超过已收货数量才成功。
     * 两张发票同时开同一批已收货的数量，只有一张能占到；另一张按数量差异处理，不会同一批货付两次款。
     */
    @Update("""
            UPDATE purchase_order_line SET invoiced_qty = invoiced_qty + #{qty}
            WHERE id = #{id} AND invoiced_qty + #{qty} <= received_qty
            """)
    int occupyInvoiced(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /** 订单所有行是否都已经开票开满（用来判断订单能否关闭）。 */
    @Select("SELECT COUNT(*) = 0 FROM purchase_order_line WHERE po_id = #{poId} AND invoiced_qty < quantity")
    boolean fullyInvoiced(@Param("poId") Long poId);

    /** 订单是否已经全部收齐。 */
    @Select("SELECT COUNT(*) = 0 FROM purchase_order_line WHERE po_id = #{poId} AND received_qty < quantity")
    boolean fullyReceived(@Param("poId") Long poId);
}
