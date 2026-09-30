package com.factcheck.service;

import com.factcheck.result.PageResult;
import com.factcheck.vo.StatsOverviewVO;

/**
 * 管理员统计与日志服务接口
 */
public interface AdminStatsService {

    /**
     * 查询工作台统计概览
     */
    StatsOverviewVO overview();

    /**
     * 分页查询操作日志
     */
    PageResult pageLogs(Integer page, Integer pageSize);
}
