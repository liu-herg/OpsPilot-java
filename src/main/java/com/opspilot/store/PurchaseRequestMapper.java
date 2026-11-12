package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PurchaseRequestMapper extends BaseMapper<PurchaseRequestEntity> {

    /** 状态转移：当前状态在 from 里才改。decidedBy 不为空时一起记下审批人和时间。 */
    @Update("""
            <script>
            UPDATE purchase_request
            SET status = #{to}, updated_at = NOW(3)
            <if test="decidedBy != null">, decided_by = #{decidedBy}, decided_at = NOW(3)</if>
            WHERE id = #{id}
              AND status IN <foreach collection="from" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    int transition(@Param("id") Long id, @Param("from") List<String> from, @Param("to") String to,
                   @Param("decidedBy") String decidedBy);
}
