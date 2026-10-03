package com.factcheck.service;

import com.factcheck.dto.AdminAccountCreateDTO;
import com.factcheck.result.PageResult;
import com.factcheck.vo.AdminBriefVO;

import java.util.List;

/**
 * 管理员账号管理服务接口
 */
public interface AdminAccountService {

    /**
     * 创建管理员账号
     */
    void create(AdminAccountCreateDTO adminAccountCreateDTO);

    /**
     * 查询管理员简要列表（P3-35：诊断转派下拉框）
     */
    List<AdminBriefVO> listAdmins();

    /**
     * 分页查询用户列表
     */
    PageResult pageUsers(Integer page, Integer pageSize);

    /**
     * 启用/禁用用户
     */
    void updateUserStatus(Long userId, Integer status);
}
