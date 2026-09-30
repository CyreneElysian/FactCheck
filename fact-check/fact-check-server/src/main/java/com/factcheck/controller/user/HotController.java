package com.factcheck.controller.user;

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
 * 热门消息控制器（用户端）
 */
@RestController
@RequestMapping("/hot")
@Slf4j
public class HotController {

    @Autowired
    private HotService hotService;

    /**
     * 查询热门消息Top10
     */
    @GetMapping("/top10")
    public Result<List<HotClaimVO>> top10() {
        log.info("查询热门消息Top10");
        return Result.success(hotService.top10());
    }
}
