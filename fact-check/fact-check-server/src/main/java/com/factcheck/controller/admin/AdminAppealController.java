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
 * 申诉处理控制器（管理端）
 */
@RestController
@RequestMapping("/admin/appeals")
@Slf4j
public class AdminAppealController {

    @Autowired
    private AdminAppealService adminAppealService;

    /**
     * 分页查询申诉
     */
    @GetMapping("")
    public Result<PageResult> page(AdminAppealPageQueryDTO pageQueryDTO) {
        log.info("分页查询申诉：{}", pageQueryDTO);
        return Result.success(adminAppealService.page(pageQueryDTO));
    }
    /**
     * 处理申诉
     */
    @PostMapping("/{id}/handle")
    public Result handle(@PathVariable Long id, @RequestBody AdminAppealHandleDTO handleDTO) {
        log.info("处理申诉：{}", id, handleDTO);
        adminAppealService.handle(id, handleDTO);
        return Result.success();
    }
}
