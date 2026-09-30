package com.factcheck.service;

import com.factcheck.dto.AdminAppealHandleDTO;
import com.factcheck.dto.AdminAppealPageQueryDTO;
import com.factcheck.result.PageResult;

/**
 * 管理员申诉处理服务接口
 */
public interface AdminAppealService {

    /**
     * 分页查询申诉
     */
    PageResult page(AdminAppealPageQueryDTO pageQueryDTO);

    /**
     * 处理申诉（采纳/驳回）
     */
    void handle(Long appealId, AdminAppealHandleDTO handleDTO);
}
