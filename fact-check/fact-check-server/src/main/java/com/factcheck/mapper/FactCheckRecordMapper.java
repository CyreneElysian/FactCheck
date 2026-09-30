package com.factcheck.mapper;

import com.factcheck.entity.FactCheckRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检测记录表 Mapper
 */
@Mapper
public interface FactCheckRecordMapper {

    /**
     * 根据内容哈希查询记录（精确去重用）
     * @param contentHash 内容 SHA-256
     * @return 检测记录实体
     */
    FactCheckRecord getByContentHash(String contentHash);

    /**
     * 根据主键查询记录
     * @param id 记录ID
     * @return 检测记录实体
     */
    FactCheckRecord getById(Long id);

    /**
     * 新增检测记录
     * @param record 检测记录实体
     */
    void insert(FactCheckRecord record);

    /**
     * 按主键动态更新记录（只更新非空字段）
     * @param record 检测记录实体
     */
    void update(FactCheckRecord record);

    /**
     * 命中次数 +1
     * @param id 记录ID
     */
    void updateHitCount(@Param("id") Long id);

    /**
     * 将失败记录重置为检测中，重新检测
     * @param id 记录ID
     */
    void resetToDetecting(@Param("id") Long id);

    /**
     * 分页查询用户检测记录
     * @param userId 用户ID
     * @param category 分类
     * @param status 状态
     * @return 记录列表
     */
    List<FactCheckRecord> page(@Param("userId") Long userId,
                               @Param("category") String category,
                               @Param("status") Integer status);

    /**
     * 根据状态查询记录（按热度/命中次数倒序）
     * @param status 状态
     * @return 记录列表
     */
    List<FactCheckRecord> listByStatus(@Param("status") Integer status);

    /**
     * 管理端分页查询检测记录
     * @param category 分类
     * @param status 状态
     * @param verdict 结论
     * @param keyword 内容关键字
     * @return 记录列表
     */
    List<FactCheckRecord> adminPage(@Param("category") String category,
                                    @Param("status") Integer status,
                                    @Param("verdict") Integer verdict,
                                    @Param("keyword") String keyword);

    /**
     * 按状态统计记录数
     */
    @Select("select count(*) from t_fact_check_record where status = #{status}")
    int countByStatus(Integer status);

    /**
     * 统计指定时间内指定判定来源的已完成记录数
     */
    @Select("select count(*) from t_fact_check_record where verdict_source = #{verdictSource} " +
            "and status = 2 and complete_time >= #{start} and complete_time <= #{end}")
    int countByVerdictSourceAndTime(@Param("verdictSource") Integer verdictSource,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);
}
