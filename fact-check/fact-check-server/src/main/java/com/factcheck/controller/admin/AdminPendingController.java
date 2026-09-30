package com.factcheck.controller.admin;

import com.factcheck.dto.*;
import com.factcheck.result.PageResult;
import com.factcheck.result.Result;
import com.factcheck.service.*;
import com.factcheck.vo.*;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 待诊断消息控制器（管理端）
 */
@RestController
@RequestMapping("/admin/pending")
@Slf4j
public class AdminPendingController {

    @Autowired
    private AdminPendingService adminPendingService;

    /**
     * 查询待诊断热门消息Top10
     */
    @GetMapping("/top10")
    public Result<List<PendingClaimVO>> top10() {
        log.info("查询待诊断热门消息Top10");
        return Result.success(adminPendingService.top10());
    }
    /**
     * 查询待诊断消息详情
     */
    @GetMapping("/{id}")
    public Result<DetectRecordVO> detail(@PathVariable Long id) {
        log.info("查询待诊断消息详情：{}", id);
        return Result.success(adminPendingService.detail(id));
    }
    /**
     * 转派待诊断消息
     */
    @PutMapping("/{id}/assign")
    public Result assign(@PathVariable Long id, @RequestBody AdminAssignDTO adminAssignDTO) {
        log.info("转派待诊断消息：{},{}", id, adminAssignDTO);
        adminPendingService.assign(id, adminAssignDTO.getAssigneeId());
        return Result.success();
    }
}
