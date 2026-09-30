package com.factcheck.mapper;

import com.factcheck.entity.UserFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 收藏表 Mapper
 */
@Mapper
public interface UserFavoriteMapper {

    /**
     * 新增收藏
     */
    @Insert("insert into t_user_favorite(user_id, record_id, create_time) " +
            "values(#{userId}, #{recordId}, #{createTime})")
    void insert(UserFavorite userFavorite);

    /**
     * 取消收藏
     */
    @Delete("delete from t_user_favorite where user_id = #{userId} and record_id = #{recordId}")
    void deleteByUserAndRecord(@Param("userId") Long userId, @Param("recordId") Long recordId);

    /**
     * 分页查询用户收藏（配合 PageHelper）
     */
    @Select("select id, user_id, record_id, create_time from t_user_favorite " +
            "where user_id = #{userId} order by create_time desc")
    List<UserFavorite> pageByUser(Long userId);

    /**
     * 统计用户是否已收藏某记录
     */
    @Select("select count(*) from t_user_favorite where user_id = #{userId} and record_id = #{recordId}")
    int countByUserAndRecord(@Param("userId") Long userId, @Param("recordId") Long recordId);
}
