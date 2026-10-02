package com.factcheck.mapper;

import com.factcheck.entity.ContentSafetyLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/**
 * 内容安全审核日志表 Mapper
 *
 * <p>说明：本项目其余实体 Mapper 走 XML（{@code resources/mapper/*.xml}），
 * 这里只有一个插入方法，直接用注解更直观，也避免为单条语句新增 XML 文件。</p>
 */
@Mapper
public interface ContentSafetyLogMapper {

    /**
     * 写入一条审核日志
     *
     * @param log 审核日志（createTime 由调用方填充，不依赖数据库默认值，便于测试断言）
     * @return 影响行数
     */
    @Insert("INSERT INTO t_content_safety_log"
            + "(user_id, content_hash, source_type, check_type, result, reason, create_time) "
            + "VALUES(#{userId}, #{contentHash}, #{sourceType}, #{checkType}, #{result}, #{reason}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContentSafetyLog log);
}
