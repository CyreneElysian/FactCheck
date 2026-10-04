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
     * 原子"认领"申诉单：仅当仍是"待处理"时把它推进为已处理，返回影响行数
     *
     * <p>为什么需要它：处理申诉的流程是"先 getById 校验 status==0，再 update"，
     * 两步之间没有任何保护——两个管理员同时点"采纳"会**双双通过校验**，
     * 于是结论被改两次、写两条版本快照、给用户发两条站内信。
     * 把状态流转做成带条件的 UPDATE 后，数据库的行锁保证了只有一个请求能把
     * status 从 0 改成目标值，后者拿到 0 行、当场失败。</p>
     *
     * @param id        申诉 ID
     * @param newStatus 目标状态（1 采纳 / 2 驳回）
     * @return 影响行数：1 表示认领成功，0 表示已被别人处理过
     */
    int markHandledIfPending(@Param("id") Long id,
                             @Param("newStatus") Integer newStatus,
                             @Param("handleResult") String handleResult,
                             @Param("handledBy") Long handledBy);

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
