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
 * 结果评分控制器（用户端）
 */
@RestController
@RequestMapping("/rating")
@Slf4j
public class RatingController {

    @Autowired
    private RatingService ratingService;

    /**
     * 提交/修改检测结果评分
     */
    @PostMapping("")
    public Result submit(@RequestBody RatingSubmitDTO ratingSubmitDTO) {
        log.info("提交/修改检测结果评分：{}", ratingSubmitDTO);
        ratingService.submit(ratingSubmitDTO);
        return Result.success();
    }
}
