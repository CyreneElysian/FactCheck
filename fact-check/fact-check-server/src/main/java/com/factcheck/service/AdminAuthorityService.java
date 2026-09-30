package com.factcheck.service;

import com.factcheck.dto.AdminAuthorityDTO;
import com.factcheck.result.PageResult;

/**
 * 管理员权威信息管理服务接口
 */
public interface AdminAuthorityService {

    /**
     * 分页查询权威信息（含草稿/下线）
     */
    PageResult page(Integer page, Integer pageSize);

    /**
     * 新增权威信息
     */
    void save(AdminAuthorityDTO adminAuthorityDTO);

    /**
     * 编辑权威信息
     */
    void update(AdminAuthorityDTO adminAuthorityDTO);

    /**
     * 上/下线权威信息
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除权威信息
     */
    void delete(Long id);
}
