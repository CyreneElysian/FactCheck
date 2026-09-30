package com.factcheck.mapper;

import com.factcheck.entity.OperationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 操作日志表 Mapper
 */
@Mapper
public interface OperationLogMapper {

    /**
     * 新增操作日志
     * @param operationLog 操作日志实体
     */
    @Insert("insert into t_operation_log(admin_id, operation, method, params, ip, create_time) " +
            "values(#{adminId}, #{operation}, #{method}, #{params}, #{ip}, #{createTime})")
    void insert(OperationLog operationLog);

    /**
     * 查询操作日志（配合 PageHelper 使用）
     */
    @Select("select id, admin_id, operation, method, params, ip, create_time " +
            "from t_operation_log order by create_time desc")
    List<OperationLog> page();
}
