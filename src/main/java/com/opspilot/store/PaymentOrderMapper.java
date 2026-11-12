package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrderEntity> {

    @Select("SELECT * FROM payment_order WHERE id = #{id} FOR UPDATE")
    PaymentOrderEntity lockById(@Param("id") Long id);

    /** 某张采购订单最早的一张待付款付款单（快捷入口"处理 PO-xxx 的付款"用），加锁。 */
    @Select("""
            SELECT p.* FROM payment_order p JOIN purchase_order o ON o.id = p.po_id
            WHERE o.po_number = #{poNumber} AND p.status = 'PENDING'
            ORDER BY p.id LIMIT 1
            FOR UPDATE OF p
            """)
    PaymentOrderEntity lockFirstPendingByPoNumber(@Param("poNumber") String poNumber);

    @Select("SELECT * FROM payment_order WHERE run_id = #{runId}")
    PaymentOrderEntity selectByRun(@Param("runId") String runId);

    /** 开始执行：PENDING → EXECUTING，记下运行。并发发起同一张付款单只有一个成功。 */
    @Update("""
            UPDATE payment_order SET status = 'EXECUTING', run_id = #{runId}, updated_at = NOW(3)
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int startExecuting(@Param("id") Long id, @Param("runId") String runId);

    /** 运行结束后同步：只改正在由这个运行执行的付款单。 */
    @Update("""
            UPDATE payment_order SET status = #{to}, finance_payment_id = #{financePaymentId}, updated_at = NOW(3)
            WHERE run_id = #{runId} AND status = 'EXECUTING'
            """)
    int finishByRun(@Param("runId") String runId, @Param("to") String to,
                    @Param("financePaymentId") String financePaymentId);

    /** 失败后重新发起：FAILED → PENDING。 */
    @Update("UPDATE payment_order SET status = 'PENDING', updated_at = NOW(3) WHERE id = #{id} AND status = 'FAILED'")
    int reopen(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM payment_order WHERE po_id = #{poId} AND status <> 'PAID'")
    int countUnpaid(@Param("poId") Long poId);
}
