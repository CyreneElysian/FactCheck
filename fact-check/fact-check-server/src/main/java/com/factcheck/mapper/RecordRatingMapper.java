package com.factcheck.mapper;

import com.factcheck.entity.RecordRating;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 结果评分表 Mapper
 */
@Mapper
public interface RecordRatingMapper {

    /**
     * 查询用户对某记录的评分
     */
    @Select("select id, user_id, record_id, rating, create_time, update_time from t_record_rating " +
            "where user_id = #{userId} and record_id = #{recordId}")
    RecordRating getByUserAndRecord(@Param("userId") Long userId, @Param("recordId") Long recordId);

    /**
     * 新增评分
     */
    @Insert("insert into t_record_rating(user_id, record_id, rating, create_time, update_time) " +
            "values(#{userId}, #{recordId}, #{rating}, #{createTime}, #{updateTime})")
    void insert(RecordRating recordRating);

    /**
     * 修改评分
     */
    @Update("update t_record_rating set rating = #{rating}, update_time = now() where id = #{id}")
    void updateRating(@Param("id") Long id, @Param("rating") Integer rating);
}
