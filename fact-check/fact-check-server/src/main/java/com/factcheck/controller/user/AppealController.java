package com.factcheck.controller.user;

import com.factcheck.dto.*;
import com.factcheck.result.PageResult;
import com.factcheck.result.Result;
import com.factcheck.service.*;
import com.factcheck.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 申诉控制器（用户端）
 */
@RestController
@RequestMapping("/appeal")
@Slf4j
public class AppealController {

    @Autowired
    private AppealService appealService;

    /**
     * 用户发起申诉
     */
    @PostMapping("")
    public Result submit(@RequestBody AppealSubmitDTO appealSubmitDTO) {
        log.info("用户发起申诉：{}", appealSubmitDTO);
        appealService.submit(appealSubmitDTO);
        return Result.success();
    }
    /**
     * 分页查询我的申诉
     */
    @GetMapping("/my")
    public Result<PageResult> my(AppealPageQueryDTO appealPageQueryDTO) {
        log.info("分页查询我的申诉：{}", appealPageQueryDTO);
        return Result.success(appealService.pageMy(appealPageQueryDTO));
    }
}
