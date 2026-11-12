package com.opspilot.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SupplierMapper extends BaseMapper<SupplierEntity> {

    /** 状态转移：只有当前状态是 from 才改。 */
    @Update("UPDATE supplier SET status = #{to}, updated_at = NOW(3) WHERE id = #{id} AND status = #{from}")
    int transition(@Param("id") Long id, @Param("from") String from, @Param("to") String to);
}
