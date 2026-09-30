package com.factcheck.mapper;

import com.factcheck.entity.Message;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 站内信表 Mapper
 */
@Mapper
public interface MessageMapper {

    /**
     * 新增站内信
     */
    @Insert("insert into t_message(user_id, type, title, content, related_id, is_read, create_time) " +
            "values(#{userId}, #{type}, #{title}, #{content}, #{relatedId}, #{isRead}, #{createTime})")
    void insert(Message message);

    /**
     * 分页查询用户站内信（配合 PageHelper）
     */
    @Select("<script>select id, user_id, type, title, content, related_id, is_read, create_time " +
            "from t_message where user_id = #{userId} " +
            "<if test='isRead != null'>and is_read = #{isRead}</if> " +
            "order by create_time desc</script>")
    List<Message> pageByUser(@Param("userId") Long userId, @Param("isRead") Integer isRead);

    /**
     * 标记单条已读
     */
    @Update("update t_message set is_read = 1 where id = #{id} and user_id = #{userId}")
    int read(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 全部已读
     */
    @Update("update t_message set is_read = 1 where user_id = #{userId} and is_read = 0")
    int readAll(@Param("userId") Long userId);

    /**
     * 统计未读消息数
     */
    @Select("select count(*) from t_message where user_id = #{userId} and is_read = 0")
    int countUnread(@Param("userId") Long userId);
}
