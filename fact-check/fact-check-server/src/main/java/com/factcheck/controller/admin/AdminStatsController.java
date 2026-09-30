package com.factcheck.controller.admin;

import com.factcheck.dto.*;
import com.factcheck.result.PageResult;
import com.factcheck.result.Result;
import com.factcheck.service.*;
import com.factcheck.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 统计与日志控制器（管理端）
 */
@RestController
@RequestMapping("/admin/stats")
@Slf4j
public class AdminStatsController {

    @Autowired
    private AdminStatsService adminStatsService;

    /**
     * 查询工作台统计概览
     */
    @GetMapping("/overview")
    public Result<StatsOverviewVO> overview() {
        log.info("查询工作台统计概览");
        return Result.success(adminStatsService.overview());
    }
    /**
     * 分页查询操作日志
     */
    @GetMapping("/logs")
    public Result<PageResult> logs(@RequestParam Integer page, @RequestParam Integer pageSize) {
        log.info("分页查询操作日志：{},{}", page, pageSize);
        return Result.success(adminStatsService.pageLogs(page, pageSize));
    }
}
