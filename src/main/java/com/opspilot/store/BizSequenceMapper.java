package com.opspilot.store;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 业务单号序列。{@link #bump} 和 {@link #lastValue} 必须在<b>同一个连接</b>上执行（LAST_INSERT_ID 是连接级的），
 * 所以只能在事务里调用。日期由调用方从数据库取一次后显式传入，几条语句用的是同一天。
 */
@Mapper
public interface BizSequenceMapper {

    /** 数据库当天日期（yyyy-MM-dd）。普通读，不加锁。 */
    @Select("SELECT DATE_FORMAT(CURDATE(), '%Y-%m-%d')")
    String today();

    /**
     * 这一天的行不存在时插入（值为 0）。由调用方放在独立的短事务里执行，提交后马上释放锁。
     * 不用 INSERT ... ON DUPLICATE KEY UPDATE：多个事务同时插入同一个新键时，
     * 它们都会先拿共享锁、再争排他锁，是 MySQL 里典型的死锁场景。
     */
    @Insert("INSERT IGNORE INTO biz_sequence (name, day, value) VALUES (#{name}, #{day}, 0)")
    int ensure(@Param("name") String name, @Param("day") String day);

    /**
     * 原子递增，并把新值存进本连接的 LAST_INSERT_ID。行已存在时直接拿排他锁，并发取号排队，不会死锁。
     * 注意：行不存在时这条 UPDATE 会加间隙锁，所以必须先确保行存在再调用。
     */
    @Update("UPDATE biz_sequence SET value = LAST_INSERT_ID(value + 1) WHERE name = #{name} AND day = #{day}")
    int bump(@Param("name") String name, @Param("day") String day);

    @Select("SELECT LAST_INSERT_ID()")
    long lastValue();
}
