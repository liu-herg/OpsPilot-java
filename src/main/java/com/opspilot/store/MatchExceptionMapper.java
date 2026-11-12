package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MatchExceptionMapper extends BaseMapper<MatchExceptionEntity> {

    @Select("SELECT * FROM match_exception WHERE invoice_id = #{invoiceId} ORDER BY id")
    List<MatchExceptionEntity> selectByInvoice(@Param("invoiceId") Long invoiceId);

    /** 处理差异：只有 OPEN 的能处理，并发处理同一条只有一个成功。 */
    @Update("""
            UPDATE match_exception SET status = #{to}, resolver = #{resolver}, resolved_at = NOW(3)
            WHERE id = #{id} AND status = 'OPEN'
            """)
    int resolve(@Param("id") Long id, @Param("to") String to, @Param("resolver") String resolver);

    /** 发票的其余 OPEN 差异一起关闭（驳回发票、重新匹配通过时）。 */
    @Update("""
            UPDATE match_exception SET status = #{to}, resolver = #{resolver}, resolved_at = NOW(3)
            WHERE invoice_id = #{invoiceId} AND status = 'OPEN'
            """)
    int resolveAllOpen(@Param("invoiceId") Long invoiceId, @Param("to") String to, @Param("resolver") String resolver);

    @Select("SELECT COUNT(*) FROM match_exception WHERE invoice_id = #{invoiceId} AND status = 'OPEN'")
    int countOpen(@Param("invoiceId") Long invoiceId);
}
