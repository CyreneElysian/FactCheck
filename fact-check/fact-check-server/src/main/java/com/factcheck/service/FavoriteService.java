package com.factcheck.service;

import com.factcheck.result.PageResult;

/**
 * 收藏服务接口
 */
public interface FavoriteService {

    /**
     * 收藏检测结果
     */
    void add(Long recordId);

    /**
     * 取消收藏
     */
    void remove(Long recordId);

    /**
     * 分页查询我的收藏
     */
    PageResult pageMy(Integer page, Integer pageSize);
}
