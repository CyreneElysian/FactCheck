package com.factcheck.service;

import com.factcheck.dto.AuthorityPageQueryDTO;
import com.factcheck.result.PageResult;
import com.factcheck.vo.AuthorityInfoVO;

/**
 * 权威信息服务接口（用户端）
 */
public interface AuthorityService {

    /**
     * 分页查询已发布权威信息
     */
    PageResult page(AuthorityPageQueryDTO pageQueryDTO);

    /**
     * 查询权威信息详情
     */
    AuthorityInfoVO detail(Long id);
}
