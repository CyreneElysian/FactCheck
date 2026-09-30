package com.factcheck.mapper;

import com.factcheck.entity.DetectLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 检测请求日志表 Mapper
 */
@Mapper
public interface DetectLogMapper {

    /**
     * 新增检测请求日志
     * @param detectLog 日志实体
     */
    @Insert("insert into t_detect_log(user_id, content, content_hash, category, source_type, dedup_hit, " +
            "hit_record_id, record_id, ip, user_agent, create_time) " +
            "values(#{userId}, #{content}, #{contentHash}, #{category}, #{sourceType}, #{dedupHit}, " +
            "#{hitRecordId}, #{recordId}, #{ip}, #{userAgent}, #{createTime})")
    void insert(DetectLog detectLog);

    /**
     * 统计时间段内的检测请求数
     */
    @Select("select count(*) from t_detect_log where create_time >= #{start} and create_time <= #{end}")
    int countByCreateTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
