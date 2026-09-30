package com.factcheck.mapper;

import com.factcheck.entity.Appeal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 申诉表 Mapper
 */
@Mapper
public interface AppealMapper {

    /**
     * 根据主键查询申诉
     * @param id 申诉ID
     * @return 申诉实体
     */
    Appeal getById(Long id);

    /**
     * 分页查询申诉（管理端）
     * @param status 状态
     * @return 申诉列表
     */
    List<Appeal> page(@Param("status") Integer status);

    /**
     * 新增申诉
     * @param appeal 申诉实体
     */
    void insert(Appeal appeal);

    /**
     * 按主键动态更新申诉（只更新非空字段）
     * @param appeal 申诉实体
     */
    void update(Appeal appeal);

    /**
     * 分页查询指定用户的申诉
     */
    List<Appeal> pageByUser(@Param("userId") Long userId, @Param("status") Integer status);

    /**
     * 查询用户对某条记录的最近一次申诉
     */
    @Select("select id, record_id, user_id, reason_type, content, status, handle_result, " +
            "handled_by, handle_time, create_time from t_appeal " +
            "where record_id = #{recordId} and user_id = #{userId} order by create_time desc limit 1")
    Appeal getByRecordIdAndUser(@Param("recordId") Long recordId, @Param("userId") Long userId);

    /**
     * 按状态统计申诉数
     */
    @Select("select count(*) from t_appeal where status = #{status}")
    int countByStatus(Integer status);
}
